use syn::{Type, TypePath};
use xross_metadata::{XrossType, Ownership};

pub fn map_type(ty: &Type) -> XrossType {
    match ty {
        // 参照 (&T) の場合、再帰的に中身の型を判定する。
        // ※所有権(Ownership)の最終的な決定は、呼び出し元の resolve_type_with_attr で行われる。
        Type::Reference(r) => map_type(&r.elem),

        Type::Path(TypePath { path, .. }) => {
            let segments: Vec<String> = path.segments.iter()
                .map(|s| s.ident.to_string())
                .collect();
            let full_path = segments.join(".");
            let last_segment = segments.last().unwrap();

            match last_segment.as_str() {
                "i8" => XrossType::I8,
                "i16" => XrossType::I16,
                "i32" => XrossType::I32,
                "i64" => XrossType::I64,
                "u16" => XrossType::U16,
                "f32" => XrossType::F32,
                "f64" => XrossType::F64,
                "bool" => XrossType::Bool,
                "String" => XrossType::String,

                // 構造体や列挙型（大文字開始）の場合
                s if s.chars().next().map_or(false, |c| c.is_uppercase()) => {
                    // デフォルトは Owned で生成。後ほど resolve_type_with_attr で書き換えられる。
                    XrossType::Object {
                        signature: full_path,
                        ownership: Ownership::Owned,
                    }
                }
                _ => XrossType::Pointer,
            }
        }
        // タプルやスライス、あるいは想定外の型は一旦 Pointer 扱い
        _ => XrossType::Pointer,
    }
}

