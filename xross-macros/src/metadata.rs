use std::fs;
use std::hash::Hash;
use std::hash::Hasher;
use std::path::PathBuf;
use std::sync::Once;
use xross_metadata::XrossDefinition;

static INIT: Once = Once::new();

/// Returns the directory where xross metadata files are stored.
/// The first time this is called, the directory is cleared and recreated.
pub fn get_xross_dir() -> PathBuf {
    let xross_dir = resolve_xross_dir();

    // 初回呼び出し時のみ実行されるスレッド安全な初期化
    INIT.call_once(|| {
        if xross_dir.exists() {
            let _ = fs::remove_dir_all(&xross_dir);
        }
        let _ = fs::create_dir_all(&xross_dir);
    });

    xross_dir
}

// パス特定ロジックのみを分離（再利用と可読性のため）
fn resolve_xross_dir() -> PathBuf {
    // 1. 明示的な環境変数を最優先
    let crate_name = std::env::var("CARGO_PKG_NAME").unwrap_or_else(|_| "common".into());

    if let Ok(val) = std::env::var("XROSS_METADATA_DIR") {
        return PathBuf::from(val);
    }

    // 2. OUT_DIR から target を特定
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let path = PathBuf::from(out_dir);
        for ancestor in path.ancestors() {
            if ancestor.file_name().and_then(|n| n.to_str()) == Some("target") {
                return ancestor.join("xross").join(crate_name);
            }
        }
    }

    // 3. CARGO_TARGET_DIR を確認
    if let Ok(val) = std::env::var("CARGO_TARGET_DIR") {
        return PathBuf::from(val).join("xross").join(crate_name);
    }

    // 4. マニフェストの場所から推測
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::current_dir().unwrap());

    for ancestor in manifest_dir.ancestors() {
        let target_path = ancestor.join("target");
        if target_path.is_dir() {
            return target_path.join("xross").join(crate_name);
        }
    }

    manifest_dir.join("target").join("xross").join(crate_name)
}

/// Returns the file path for a given signature.
pub fn get_path_by_signature(signature: &str) -> PathBuf {
    get_xross_dir().join(format!("{}.json", signature))
}

/// Saves the type definition to a JSON file in the metadata directory.
/// Performs compatibility checks if a definition already exists.
pub fn save_definition(def: &XrossDefinition) {
    let xross_dir = get_xross_dir();
    // 1. ディレクトリ作成（既存なら何もしない）
    fs::create_dir_all(&xross_dir).ok();

    let signature = def.signature();
    let path = get_path_by_signature(signature);

    // 2. 既存チェック（他プロセスによる書き込み完了を考慮）
    // NOTE: path.exists() が true の時点で、renameが完了していることが保証されます
    if path.exists() {
        if let Ok(content) = fs::read_to_string(&path) {
            if let Ok(existing_def) = serde_json::from_str::<XrossDefinition>(&content) {
                if !is_structurally_compatible(&existing_def, def) {
                    panic!("\n[Xross Error] Duplicate definition: '{}'\n", signature);
                }
                return;
            }
        }
    }

    // 3. 一時ファイルの書き込み
    if let Ok(json) = serde_json::to_string(def) {
        let mut hasher = std::hash::DefaultHasher::new();
        std::time::Instant::now().hash(&mut hasher);
        std::thread::current().id().hash(&mut hasher);
        let temp_path = xross_dir.join(format!("tmp_{:016x}.json", hasher.finish()));

        if fs::write(&temp_path, json).is_ok() {
            // 4. アトミックな確定
            // 既に存在していても上書きされる（OSレベルでアトミック）
            if let Err(_) = fs::rename(&temp_path, &path) {
                // リネームに失敗した場合（他プロセスが同一ファイルを処理中など）
                // 一時ファイルを残さないように掃除だけする
                let _ = fs::remove_file(&temp_path);
            }
        }
    }
}

/// Checks if two definitions are structurally compatible.
fn is_structurally_compatible(a: &XrossDefinition, b: &XrossDefinition) -> bool {
    match (a, b) {
        (XrossDefinition::Struct(sa), XrossDefinition::Struct(sb)) => {
            sa.package_name == sb.package_name
                && sa.name == sb.name
                && sa.fields.len() == sb.fields.len()
        }
        (XrossDefinition::Enum(ea), XrossDefinition::Enum(eb)) => {
            ea.package_name == eb.package_name
                && ea.name == eb.name
                && ea.variants.len() == eb.variants.len()
        }
        (XrossDefinition::Opaque(oa), XrossDefinition::Opaque(ob)) => {
            oa.package_name == ob.package_name && oa.name == ob.name
        }
        (XrossDefinition::Function(fa), XrossDefinition::Function(fb)) => {
            fa.package_name == fb.package_name && fa.name == fb.name
        }
        _ => false,
    }
}

/// Loads a definition from the metadata directory by its identifier name.
pub fn load_definition(ident: &syn::Ident) -> Option<XrossDefinition> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    let entries = fs::read_dir(xross_dir).ok()?;
    for entry in entries.flatten() {
        let path = entry.path();

        // ファイルが .tmp の場合は無視
        if path.extension().and_then(|s| s.to_str()) == Some("tmp") {
            continue;
        }

        // 並列実行対策: ファイルが書き換え中の場合に備え、読み込み失敗時は少し待って再試行
        let mut content = fs::read_to_string(&path);
        if content.is_err() {
            std::thread::sleep(std::time::Duration::from_millis(5));
            content = fs::read_to_string(&path);
        }

        if let Ok(c) = content {
            if let Ok(def) = serde_json::from_str::<XrossDefinition>(&c) {
                if *ident == def.name() {
                    return Some(def);
                }
            }
        }
    }
    None
}

/// Discovers the signature of a type by its name.
/// Panics if multiple types with the same name are found in different packages.
pub fn discover_signature(type_name: &str) -> Option<String> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    let mut candidates = Vec::new();

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path())
                && let Ok(def) = serde_json::from_str::<XrossDefinition>(&content)
                && def.name() == type_name
            {
                candidates.push(def.signature().to_string());
            }
        }
    }

    candidates.sort();
    candidates.dedup();

    if candidates.len() == 1 {
        Some(candidates.remove(0))
    } else if candidates.len() > 1 {
        panic!(
            "\n[Xross Error] Ambiguous type reference: '{}'\n\
             Multiple types with the same name were found in different packages:\n\
             {}\n",
            type_name,
            candidates.iter().map(|s| format!("  - {}", s)).collect::<Vec<_>>().join("\n")
        );
    } else {
        None
    }
}
