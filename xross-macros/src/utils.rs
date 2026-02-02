use std::fs;
use std::path::{Path, PathBuf};
use syn::{Expr, ExprLit, Lit, Meta};
use xross_metadata::XrossField;

pub fn get_tmp_path(struct_name: &syn::Ident) -> PathBuf {
    Path::new("target/xross/tmp").join(format!("{}_fields.json", struct_name))
}

pub fn save_struct_metadata(struct_name: &syn::Ident, fields: &Vec<XrossField>, docs: &Vec<String>) {
    fs::create_dir_all("target/xross/tmp").ok();
    let data = serde_json::json!({ "fields": fields, "docs": docs });
    fs::write(get_tmp_path(struct_name), serde_json::to_string(&data).unwrap_or_default()).ok();
}

pub fn extract_docs(attrs: &[syn::Attribute]) -> Vec<String> {
    attrs.iter()
        .filter(|a| a.path().is_ident("doc"))
        .filter_map(|a| {
            if let Meta::NameValue(nv) = &a.meta {
                if let Expr::Lit(ExprLit { lit: Lit::Str(s), .. }) = &nv.value {
                    return Some(s.value().trim().to_string());
                }
            }
            None
        }).collect()
}

pub fn load_struct_metadata(struct_name: &syn::Ident) -> (Vec<String>, Vec<XrossField>) {

    let path = get_tmp_path(struct_name);

    if let Ok(content) = fs::read_to_string(&path) {

        let _ = fs::remove_file(&path);

        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&content) {

            let docs = serde_json::from_value(val["docs"].clone()).unwrap_or_default();

            let fields = serde_json::from_value(val["fields"].clone()).unwrap_or_default();

            return (docs, fields);

        }

    }

    (vec![], vec![])

}

