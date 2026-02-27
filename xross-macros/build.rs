use std::fs;
use std::path::PathBuf;

fn resolve_xross_parent_dir() -> PathBuf {
    if let Ok(val) = std::env::var("XROSS_METADATA_DIR") {
        return PathBuf::from(val);
    }

    // 2. OUT_DIR から target を特定
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let path = PathBuf::from(out_dir);
        for ancestor in path.ancestors() {
            if ancestor.file_name().and_then(|n| n.to_str()) == Some("target") {
                return ancestor.join("xross");
            }
        }
    }

    // 3. CARGO_TARGET_DIR を確認
    if let Ok(val) = std::env::var("CARGO_TARGET_DIR") {
        return PathBuf::from(val).join("xross");
    }

    // 4. マニフェストの場所から推測
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::current_dir().unwrap());

    for ancestor in manifest_dir.ancestors() {
        let target_path = ancestor.join("target");
        if target_path.is_dir() {
            return target_path.join("xross");
        }
    }

    manifest_dir.join("target").join("xross")
}

fn main() {
    let xross_dir = resolve_xross_parent_dir();

    if !xross_dir.exists()
        && let Err(e) = fs::create_dir_all(&xross_dir)
    {
        eprintln!("cargo:warning=Failed to create directory {:?}: {}", xross_dir, e);
    }

    println!("cargo:rerun-if-env-changed=XROSS_METADATA_DIR");
    println!("cargo:rerun-if-env-changed=CARGO_TARGET_DIR");
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/utils.rs");
}
