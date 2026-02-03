use crate::{XrossField, XrossMethod};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum XrossDefinition {
    Struct(XrossStruct),
    Enum(XrossEnum),
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossStruct {
    pub signature: String,
    pub symbol_prefix: String,
    pub package_name: String,
    pub name: String,
    pub fields: Vec<XrossField>,
    pub methods: Vec<XrossMethod>,
    pub docs: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossEnum {
    pub signature: String,
    pub symbol_prefix: String,
    pub package_name: String,
    pub name: String,
    pub variants: Vec<XrossVariant>,
    pub methods: Vec<XrossMethod>,
    pub docs: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossVariant {
    pub name: String,
    pub fields: Vec<XrossField>, // 名前なしフィールドは "0", "1" と命名
    pub docs: Vec<String>,
}
