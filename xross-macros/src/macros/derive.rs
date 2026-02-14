pub mod enum_impl;
pub mod struct_impl;

use proc_macro2::TokenStream;
use syn::Item;

pub fn impl_xross_class_derive(input: Item) -> TokenStream {
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let mut extra_functions = Vec::new();

    let derive_toks = match input {
        Item::Struct(s) => struct_impl::impl_struct_derive(&s, &crate_name, &mut extra_functions),
        Item::Enum(e) => enum_impl::impl_enum_derive(&e, &crate_name, &mut extra_functions),
        _ => panic!("#[derive(XrossClass)] only supports Struct and Enum"),
    };

    quote::quote! {
        #derive_toks
        #(#extra_functions)*
    }
}
