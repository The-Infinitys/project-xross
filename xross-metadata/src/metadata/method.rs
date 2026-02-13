mod types;
pub use types::*;

use crate::metadata::ThreadSafety;
use crate::{XrossField, XrossType};
use serde::{Deserialize, Serialize};

/// Metadata for a method to be bridged to JVM.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossMethod {
    /// Name of the method.
    pub name: String,
    /// Native symbol name.
    pub symbol: String,
    /// Type of the method (Static, Instance, etc.).
    pub method_type: XrossMethodType,
    /// Whether this method is a constructor.
    pub is_constructor: bool,
    /// Arguments of the method.
    pub args: Vec<XrossField>,
    /// Return type of the method.
    pub ret: XrossType,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
    /// Thread safety level for calling this method.
    pub safety: ThreadSafety,
}
