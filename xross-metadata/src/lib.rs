use serde::{Serialize, Deserialize};

// --- メタデータ定義 ---

#[derive(Debug, Serialize, Deserialize)]
pub struct FieldMetadata {
    pub name: String,
    pub rust_type: String,
    pub ffi_getter_name: String,
    pub ffi_setter_name: String,
    pub ffi_type: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct MethodMetadata {
    pub name: String,
    pub ffi_name: String,
    pub args: Vec<String>,
    pub return_type: String,
    pub has_self: bool,
    pub is_static: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct StructMetadata {
    pub name: String,
    pub ffi_prefix: String,
    pub new_fn_name: String,
    pub drop_fn_name: String,
    pub clone_fn_name: String,
    pub fields: Vec<FieldMetadata>,
    pub methods: Vec<MethodMetadata>,
}

// トップレベルの統合メタデータ構造体
#[derive(Debug, Serialize, Deserialize)]
pub struct XrossCombinedMetadata {
    pub structs: Vec<StructMetadata>,
    pub methods: Vec<MethodMetadata>,
}