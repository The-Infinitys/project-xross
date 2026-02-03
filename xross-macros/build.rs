use std::fs;
use std::path::Path;

fn main() {
    // 削除対象のディレクトリリスト
    let dirs_to_clean = [
        "target/xross",
    ];

    for dir in &dirs_to_clean {
        let path = Path::new(dir);
        if path.exists() {
            // ディレクトリとその中身を再帰的に削除
            if let Err(e) = fs::remove_dir_all(path) {
                eprintln!("cargo:warning=Failed to delete directory {}: {}", dir, e);
            }
        }
    }

    // ディレクトリを再作成（マクロが書き込み時にエラーにならないよう）
    for dir in &dirs_to_clean {
        if let Err(e) = fs::create_dir_all(dir) {
            eprintln!("cargo:warning=Failed to create directory {}: {}", dir, e);
        }
    }

    // build.rs 自体は変更がない限り再実行されないため、
    // 常に実行したい場合は、ソース変更を監視する設定を入れない、
    // もしくは cargo:rerun-if-changed を慎重に設定します。
    println!("cargo:rerun-if-changed=src/lib.rs");
}
