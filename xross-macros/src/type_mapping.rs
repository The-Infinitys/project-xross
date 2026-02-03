use syn::{Type, TypePath};
use xross_metadata::XrossType;

pub fn map_type(ty: &Type) -> XrossType {
    match ty {
        // 参照 (&T) は中身を解析してそのまま返す（FFI境界ではポインタになるため）
        Type::Reference(r) => map_type(&r.elem),

        Type::Path(TypePath { path, .. }) => {
            let segments: Vec<String> = path.segments.iter()
                .map(|s| s.ident.to_string())
                .collect();
            let full_path = segments.join("."); // Java/Kotlin的なシグネチャへ
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
                // 大文字から始まる場合は、一旦「Object」として扱う
                s if s.chars().next().map_or(false, |c| c.is_uppercase()) => {
                    XrossType::Object {
                        signature: full_path,
                    }
                }
                _ => XrossType::Pointer,
            }
        }
        _ => XrossType::Pointer,
    }
}
