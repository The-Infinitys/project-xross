use crate::XrossType;
use crate::metadata::ThreadSafety;
use serde::{Deserialize, Serialize};

/// Metadata for a field in a struct or enum variant.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossField {
    /// Name of the field.
    pub name: String,
    /// Type of the field.
    pub ty: XrossType,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
    /// Thread safety level for accessing this field.
    pub safety: ThreadSafety,
}
