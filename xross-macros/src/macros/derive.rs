use crate::codegen::ffi::{
    generate_common_ffi, generate_property_accessors,
};
use crate::metadata::save_definition;
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::Item;
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossMethodType,
    XrossStruct, XrossType, XrossVariant,
};

pub fn impl_xross_class_derive(input: Item) -> TokenStream {
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let mut extra_functions = Vec::new();

    match input {
        Item::Struct(s) => {
            let name = &s.ident;
            let package = extract_package(&s.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            let layout_logic = crate::codegen::ffi::generate_struct_layout(&s);
            let is_clonable = extract_is_clonable(&s.attrs);

            let mut fields = Vec::new();
            let mut methods = Vec::new();

            if is_clonable {
                methods.push(XrossMethod {
                    name: "clone".to_string(),
                    symbol: format!("{}_clone", symbol_base),
                    method_type: XrossMethodType::ConstInstance,
                    is_constructor: false,
                    args: vec![],
                    ret: XrossType::Object {
                        signature: if package.is_empty() {
                            name.to_string()
                        } else {
                            format!("{}.{}", package, name)
                        },
                        ownership: Ownership::Owned,
                    },
                    safety: ThreadSafety::Lock,
                    docs: vec!["Creates a clone of the native object.".to_string()],
                });
            }

            if let syn::Fields::Named(f) = &s.fields {
                for field in &f.named {
                    if field.attrs.iter().any(|a| a.path().is_ident("xross_field")) {
                        let field_ident = field.ident.as_ref().unwrap();
                        let field_name = field_ident.to_string();
                        let xross_ty =
                            resolve_type_with_attr(&field.ty, &field.attrs, &package, Some(name));
                        fields.push(XrossField {
                            name: field_name.clone(),
                            ty: xross_ty.clone(),
                            safety: extract_safety_attr(&field.attrs, ThreadSafety::Lock),
                            docs: extract_docs(&field.attrs),
                        });

                        generate_property_accessors(
                            name,
                            field_ident,
                            &field.ty,
                            &xross_ty,
                            &symbol_base,
                            &mut extra_functions,
                        );
                    }
                }
            }
            save_definition(&XrossDefinition::Struct(XrossStruct {
                signature: if package.is_empty() {
                    name.to_string()
                } else {
                    format!("{}.{}", package, name)
                },
                symbol_prefix: symbol_base.clone(),
                package_name: package,
                name: name.to_string(),
                fields,
                methods,
                docs: extract_docs(&s.attrs),
                is_copy: extract_is_copy(&s.attrs),
            }));

            generate_common_ffi(
                name,
                &symbol_base,
                layout_logic,
                &mut extra_functions,
                is_clonable,
            );
        }

        Item::Enum(e) => {
            let name = &e.ident;
            let package = extract_package(&e.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            let layout_logic = crate::codegen::ffi::generate_enum_layout(&e);
            let is_clonable = extract_is_clonable(&e.attrs);

            let mut variants = Vec::new();
            let mut methods = Vec::new();

            if is_clonable {
                methods.push(XrossMethod {
                    name: "clone".to_string(),
                    symbol: format!("{}_clone", symbol_base),
                    method_type: XrossMethodType::ConstInstance,
                    is_constructor: false,
                    args: vec![],
                    ret: XrossType::Object {
                        signature: if package.is_empty() {
                            name.to_string()
                        } else {
                            format!("{}.{}", package, name)
                        },
                        ownership: Ownership::Owned,
                    },
                    safety: ThreadSafety::Lock,
                    docs: vec!["Creates a clone of the native object.".to_string()],
                });
            }

            for v in &e.variants {
                let v_ident = &v.ident;
                let mut v_fields = Vec::new();
                let constructor_name = format_ident!("{}_new_{}", symbol_base, v_ident);

                let mut c_param_defs = Vec::new();
                let mut internal_conversions = Vec::new();
                let mut call_args = Vec::new();

                for (i, field) in v.fields.iter().enumerate() {
                    let field_name = field
                        .ident
                        .as_ref()
                        .map(|id| id.to_string())
                        .unwrap_or_else(|| ordinal_name(i));
                    let ty = resolve_type_with_attr(&field.ty, &field.attrs, &package, Some(name));

                    v_fields.push(XrossField {
                        name: field_name,
                        ty: ty.clone(),
                        safety: ThreadSafety::Lock,
                        docs: extract_docs(&field.attrs),
                    });

                    let arg_id = format_ident!("arg_{}", i);
                    let (c_arg, conv, call_arg) =
                        crate::codegen::ffi::gen_arg_conversion(&field.ty, &arg_id, &ty);
                    c_param_defs.push(c_arg);
                    internal_conversions.push(conv);

                    if let Some(id) = &field.ident {
                        call_args.push(quote! { #id: #call_arg });
                    } else {
                        call_args.push(quote! { #call_arg });
                    }
                }

                let enum_construct = if v.fields.is_empty() {
                    quote! { #name::#v_ident }
                } else if matches!(v.fields, syn::Fields::Named(_)) {
                    quote! { #name::#v_ident { #(#call_args),* } }
                } else {
                    quote! { #name::#v_ident(#(#call_args),*) }
                };

                extra_functions.push(quote! {
                    #[unsafe(no_mangle)]
                    pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #name {
                        #(#internal_conversions)*
                        Box::into_raw(Box::new(#enum_construct))
                    }
                });

                variants.push(XrossVariant {
                    name: v_ident.to_string(),
                    fields: v_fields,
                    docs: extract_docs(&v.attrs),
                });
            }

            save_definition(&XrossDefinition::Enum(XrossEnum {
                signature: if package.is_empty() {
                    name.to_string()
                } else {
                    format!("{}.{}", package, name)
                },
                symbol_prefix: symbol_base.clone(),
                package_name: package,
                name: name.to_string(),
                variants,
                methods,
                docs: extract_docs(&e.attrs),
                is_copy: extract_is_copy(&e.attrs),
            }));

            generate_common_ffi(
                name,
                &symbol_base,
                layout_logic,
                &mut extra_functions,
                is_clonable,
            );

            let tag_fn_id = format_ident!("{}_get_tag", symbol_base);
            let variant_name_fn_id = format_ident!("{}_get_variant_name", symbol_base);
            let mut variant_arms = Vec::new();
            for v in &e.variants {
                let v_ident = &v.ident;
                let v_str = v_ident.to_string();
                variant_arms.push(quote! {
                    #name::#v_ident { .. } => #v_str,
                });
            }

            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #tag_fn_id(ptr: *const #name) -> i32 {
                    if ptr.is_null() { return -1; }
                    *(ptr as *const i32)
                }

                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #variant_name_fn_id(ptr: *const #name) -> *mut std::ffi::c_char {
                    if ptr.is_null() { return std::ptr::null_mut(); }
                    let val = &*ptr;
                    let name = match val {
                        #(#variant_arms)*
                    };
                    std::ffi::CString::new(name).unwrap().into_raw()
                }
            });
        }
        _ => panic!("#[derive(XrossClass)] only supports Struct and Enum"),
    }

    quote!(#(#extra_functions)*)
}
