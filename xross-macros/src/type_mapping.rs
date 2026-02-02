use syn::{Type, TypePath, TypeReference};
use xross_metadata::XrossType;

pub fn map_type(ty: &Type) -> XrossType {
    match ty {
        Type::Reference(TypeReference { elem, .. }) => {
            // 参照の場合 (&T)
            let mut inner = map_type(elem);
            match &mut inner {
                XrossType::Struct { is_reference, .. } => *is_reference = true,
                XrossType::Slice { is_reference, .. } => *is_reference = true,
                _ => {} // プリミティブの参照は現状ポインタ扱い等
            }
            inner
        }
        Type::Path(TypePath { path, .. }) => {
            let ty_str = quote::quote!(#path).to_string().replace(" ", "");
            match ty_str.as_str() {
                "i8" => XrossType::I8,
                "i16" => XrossType::I16,
                "i32" => XrossType::I32,
                "i64" => XrossType::I64,
                "f32" => XrossType::F32,
                "f64" => XrossType::F64,
                "bool" => XrossType::Bool,
                "String" => XrossType::String,
                "u16" => XrossType::U16,
                s if s.starts_with("Vec<") => {
                    // 簡易的な抽出ロジック（実際はsynでセグメントを解析すべき）
                    XrossType::Slice {
                        element_type: Box::new(XrossType::I32), // デフォルト。TODO: 内部型解析
                        is_reference: false,
                    }
                }
                // 自作構造体の判定（大文字から始まるなど、プロジェクトの命名規則に依存）
                s if s.chars().next().is_some_and(|c| c.is_uppercase()) => {
                    XrossType::Struct {
                        name: s.to_string(),
                        symbol_prefix: "".to_string(), // 後で解決
                        is_reference: false,
                    }
                }
                _ => XrossType::Pointer,
            }
        }
        _ => XrossType::Pointer,
    }
}
