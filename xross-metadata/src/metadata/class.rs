use crate::{XrossField, XrossMethod};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossClass {
    pub symbol_prefix: String,
    pub package_name: String,
    pub struct_name: String,
    pub docs: Vec<String>,
    pub fields: Vec<XrossField>,
    pub methods: Vec<XrossMethod>,
}
