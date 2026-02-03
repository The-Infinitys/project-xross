use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum XrossType {
    Void,
    Bool,
    I8,
    I16,
    I32,
    I64,
    U16,
    F32,
    F64,
    Pointer,
    String,
    /// Rust側で定義されたJvmClass構造体 (Signatureで一意識別)
    RustStruct {
        signature: String,
    },
    /// Rust側で定義されたJvmClass列挙型 (Signatureで一意識別)
    RustEnum {
        signature: String,
    },
    /// 不透明なRustの何か
    Object {
        signature: String,
    },
}

impl XrossType {
    pub fn size(&self) -> usize {
        match self {
            XrossType::Void => 0,
            XrossType::Bool | XrossType::I8 => 1,
            XrossType::I16 | XrossType::U16 => 2,
            XrossType::I32 | XrossType::F32 => 4,
            // RustStruct, RustEnum, Object はすべてポインタ(Address)としてやり取りする前提
            XrossType::I64
            | XrossType::F64
            | XrossType::Pointer
            | XrossType::String
            | XrossType::RustStruct { .. }
            | XrossType::RustEnum { .. }
            | XrossType::Object { .. } => 8,
        }
    }

    pub fn align(&self) -> usize {
        match self {
            XrossType::Void => 1,
            XrossType::Bool | XrossType::I8 => 1,
            XrossType::I16 | XrossType::U16 => 2,
            XrossType::I32 | XrossType::F32 => 4,
            _ => 8, // 残りはすべて 64-bit align
        }
    }

    pub fn layout_member(&self) -> String {
        match self {
            XrossType::Void => panic!("Void has no layout member"),
            XrossType::Bool | XrossType::I8 => "JAVA_BYTE".to_string(),
            XrossType::I16 => "JAVA_SHORT".to_string(),
            XrossType::U16 => "JAVA_CHAR".to_string(),
            XrossType::I32 => "JAVA_INT".to_string(),
            XrossType::I64 => "JAVA_LONG".to_string(),
            XrossType::F32 => "JAVA_FLOAT".to_string(),
            XrossType::F64 => "JAVA_DOUBLE".to_string(),
            // すべて AddressLayout として扱う
            XrossType::Pointer
            | XrossType::String
            | XrossType::RustStruct { .. }
            | XrossType::RustEnum { .. }
            | XrossType::Object { .. } => "ADDRESS".to_string(),
        }
    }
}
