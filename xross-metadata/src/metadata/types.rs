use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum XrossType {
    Void,
    Bool,
    I8,
    I16,
    I32,
    I64,
    U16, // Java Char (UTF-16)
    F32,
    F64,
    Pointer,
    String, // Rust String / &str (UTF-8)
    /// 他の構造体 (JvmClass)
    #[serde(rename_all = "camelCase")]
    Struct {
        name: String,          // Kotlin側のクラス名
        symbol_prefix: String, // drop関数などを探すための接頭辞
        is_reference: bool,    // trueなら &T (Java側でdrop禁止)
    },
    /// スライス (Vec<T> / &[T])
    #[serde(rename_all = "camelCase")]
    Slice {
        element_type: Box<XrossType>,
        is_reference: bool,    // trueなら &[T] (Java側でポインタの解放禁止)
    },
}

impl XrossType {
    /// 型ごとのバイトサイズを返す
    /// Java/PanamaのValueLayoutサイズと一致させる
    pub fn size(&self) -> usize {
        match self {
            XrossType::Void => 0,
            XrossType::Bool | XrossType::I8 => 1,
            XrossType::I16 | XrossType::U16 => 2,
            XrossType::I32 | XrossType::F32 => 4,
            XrossType::I64 | XrossType::F64 | XrossType::Pointer | XrossType::String => 8,
            XrossType::Struct { is_reference, .. } => {
                if *is_reference {
                    8 // 参照はポインタサイズ
                } else {
                    // 所有している場合はポインタとして渡されるため基本8
                    // (構造体そのものを値渡しする場合はレイアウト解析が必要だが、
                    // 現状のMyService2の設計思想ではポインタ渡しを前提とする)
                    8
                }
            }
            XrossType::Slice { .. } => 16, // Pointer(8) + Length(8)
        }
    }

    /// 型ごとのアライメントを返す
    pub fn align(&self) -> usize {
        match self {
            XrossType::Void => 1,
            XrossType::Bool | XrossType::I8 => 1,
            XrossType::I16 | XrossType::U16 => 2,
            XrossType::I32 | XrossType::F32 => 4,
            XrossType::I64 | XrossType::F64 | XrossType::Pointer | XrossType::String => 8,
            XrossType::Struct { .. } => 8,
            XrossType::Slice { .. } => 8,
        }
    }

    /// KotlinPoetの %M で使用する Panama Layout名を返す
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
            XrossType::Pointer
            | XrossType::String
            | XrossType::Struct { .. }
            | XrossType::Slice { .. } => "ADDRESS".to_string(),
        }
    }

    /// この型が「借用（Reference）」であるかどうか
    pub fn is_reference(&self) -> bool {
        match self {
            XrossType::Struct { is_reference, .. } => *is_reference,
            XrossType::Slice { is_reference, .. } => *is_reference,
            _ => false,
        }
    }
}
