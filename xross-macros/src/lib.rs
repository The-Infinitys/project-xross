extern crate proc_macro;

use proc_macro::TokenStream;
use quote::{quote, format_ident, ToTokens};
use syn::{
    parse_macro_input,
    DeriveInput,
    Data,
    Fields,
    Type,
    Visibility,
    ItemImpl,
    ImplItem,
    Meta,
    punctuated::Punctuated,
    token::Token,
};
use std::{fs, path::PathBuf};
use xross_metadata::{FieldMetadata, MethodMetadata, StructMetadata};

// --- ヘルパー関数 ---



fn is_primitive_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            let ident_str = segment.ident.to_string();
            matches!(
                ident_str.as_str(),
                "u8" | "i8" | "u16" | "i16" | "u32" | "i32" | "u64" | "i64" | "f32" | "f64" | "bool"
            )
        } else { false }
    } else { false }
}

fn is_string_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            segment.ident == "String" && segment.arguments.is_empty()
        } else { false }
    } else { false }
}

fn is_jvm_class_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            let ident_str = segment.ident.to_string();
            !(is_primitive_type(ty) || is_string_type(ty) || ident_str == "Option" || ident_str == "Vec")
        } else { false }
    } else { false }
}

fn generate_ffi_prefix(crate_name: &str, struct_name_str: &str) -> String {
    // 注: module_path!() はコンパイル時のトークンなため、マクロ内での取得には制限があります。
    // ここでは簡易的に crate_name と struct_name を結合します。
    format!("{}_{}", crate_name, struct_name_str)
}

// --- メインマクロ ---

#[proc_macro_derive(JvmClass, attributes(jvm_class))]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let ast = parse_macro_input!(input as DeriveInput);
    let name = &ast.ident;
    let name_str = name.to_string();

    let mut crate_name_opt: Option<String> = None;

    // #[jvm_class(crate = "name")] のパース (syn 2.0)
    for attr in &ast.attrs {
        if attr.path().is_ident("jvm_class") {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("crate") {
                    let value = meta.value()?;
                    let s: syn::LitStr = value.parse()?;
                    crate_name_opt = Some(s.value());
                }
                Ok(())
            });
        }
    }

    let crate_name = match crate_name_opt {
        Some(c) => c,
        None => return quote! { compile_error!("Missing #[jvm_class(crate = \"...\")]"); }.into(),
    };

    let has_repr_c = ast.attrs.iter().any(|attr| {
        if attr.path().is_ident("repr") {
            let mut found_c = false;
            // `repr` 属性の引数 (例: `(C)`) をパースする
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("C") {
                    found_c = true;
                    Ok(())
                } else {
                    // 他の repr 引数は無視
                    Ok(())
                }
            });
            found_c
        } else { false }
    });
    
    if !has_repr_c {
        return quote! { compile_error!("JvmClass derive requires #[repr(C)] for FFI compatibility."); }.into();
    }

    let has_clone_derive = ast.attrs.iter().any(|attr| {
        if attr.path().is_ident("derive") {
            eprintln!("DEBUG: has_clone_derive - attr.meta: {}", quote! { #attr.meta }.to_string());
            // attr.meta が Meta::List の場合にのみ、その中身をパースする
            if let syn::Meta::List(meta_list) = &attr.meta {
                let res = meta_list.parse_args_with(syn::punctuated::Punctuated::<syn::Path, syn::Token![,]>::parse_terminated);
                eprintln!("DEBUG: has_clone_derive - meta_list parse_args_with result: {:?}", res.is_ok());
                if let Ok(paths) = res {
                    let found = paths.iter().any(|path| {
                        eprintln!("DEBUG: has_clone_derive - checking path: {}", quote! { #path }.to_string());
                        path.is_ident("Clone")
                    });
                    eprintln!("DEBUG: has_clone_derive - found Clone: {:?}", found);
                    found
                } else {
                    false
                }
            } else {
                eprintln!("DEBUG: has_clone_derive - attr.meta is not Meta::List. Actual: {}", quote! { #attr.meta }.to_string());
                false // Meta::Path や Meta::NameValue の場合は false
            }
        } else {
            false
        }
    });
    
    if !has_clone_derive {
        return quote! { compile_error!("JvmClass derive requires #[derive(Clone)] for FFI compatibility."); }.into();
    }


    let ffi_prefix = generate_ffi_prefix(&crate_name, &name_str);
    let new_fn_name = format_ident!("{}_new", ffi_prefix);
    let drop_fn_name = format_ident!("{}_drop", ffi_prefix);
    let clone_fn_name = format_ident!("{}_clone", ffi_prefix);

    let mut getter_setter_fns = quote! {};
    let mut field_metadata_list = Vec::new();

    if let Data::Struct(data_struct) = &ast.data {
        if let Fields::Named(fields) = &data_struct.fields {
            for field in &fields.named {
                if let Visibility::Public(_) = &field.vis {
                    let field_name = field.ident.as_ref().unwrap();
                    let field_name_str = field_name.to_string();
                    let field_type = &field.ty;
                    let field_type_str = quote! { #field_type }.to_string().replace(" ", "");

                    let getter_name = format_ident!("{}_get_{}", ffi_prefix, field_name_str);
                    let setter_name = format_ident!("{}_set_{}", ffi_prefix, field_name_str);

                    let mut ffi_type_label = String::new();

                    if is_primitive_type(field_type) {
                        ffi_type_label = field_type_str.clone();
                        getter_setter_fns.extend(quote! {
                            #[no_mangle]
                            pub unsafe extern "C" fn #getter_name(ptr: *const #name) -> #field_type {
                                unsafe { (*ptr).#field_name }
                            }
                            #[no_mangle]
                            pub unsafe extern "C" fn #setter_name(ptr: *mut #name, value: #field_type) {
                                unsafe { (*ptr).#field_name = value; }
                            }
                        });
                    } else if is_string_type(field_type) {
                        ffi_type_label = "*const libc::c_char".to_string();
                        getter_setter_fns.extend(quote! {
                                                    #[no_mangle]
                                                    pub unsafe extern "C" fn #getter_name(ptr: *const #name) -> *const libc::c_char {
                                                        unsafe {
                                                            let s = &(*ptr).#field_name;
                                                            let c_str = std::ffi::CString::new(s.as_str()).unwrap();
                                                            c_str.into_raw()
                                                        }
                                                    }
                                                    #[no_mangle]
                                                    pub unsafe extern "C" fn #setter_name(ptr: *mut #name, value: *const libc::c_char) {
                                                        unsafe {
                                                            let c_str = std::ffi::CStr::from_ptr(value);
                                                            (*ptr).#field_name = c_str.to_string_lossy().into_owned();
                                                        }
                                                    }                        });
                    } else if is_jvm_class_type(field_type) {
                        ffi_type_label = format!("*mut {}", field_type_str);
                        getter_setter_fns.extend(quote! {
                            #[no_mangle]
                            pub unsafe extern "C" fn #getter_name(ptr: *const #name) -> *mut #field_type {
                                unsafe { Box::into_raw(Box::new((*ptr).#field_name.clone())) }
                            }
                            #[no_mangle]
                            pub unsafe extern "C" fn #setter_name(ptr: *mut #name, value: *mut #field_type) {
                                unsafe { (*ptr).#field_name = *Box::from_raw(value); }
                            }
                        });
                    }

                    field_metadata_list.push(FieldMetadata {
                        name: field_name_str,
                        rust_type: field_type_str,
                        ffi_getter_name: getter_name.to_string(),
                        ffi_setter_name: setter_name.to_string(),
                        ffi_type: ffi_type_label,
                    });
                }
            }
        }
    }

    // メタデータ保存
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let metadata = StructMetadata {
            name: name_str,
            ffi_prefix: ffi_prefix.clone(),
            new_fn_name: new_fn_name.to_string(),
            drop_fn_name: drop_fn_name.to_string(),
            clone_fn_name: clone_fn_name.to_string(),
            fields: field_metadata_list,
            methods: Vec::new(),
        };
        let path = PathBuf::from(out_dir).join(format!("{}_struct_metadata.json", ffi_prefix));
        let _ = fs::write(path, serde_json::to_string_pretty(&metadata).unwrap());
    }

    quote! {
        impl xross_core::JvmClassTrait for #name {
            fn new() -> Self { Self::default() }
        }
        #[no_mangle]
        pub unsafe extern "C" fn #new_fn_name() -> *mut #name { Box::into_raw(Box::new(#name::new())) }
        #[no_mangle]
        pub unsafe extern "C" fn #drop_fn_name(ptr: *mut #name) { if !ptr.is_null() { unsafe { drop(Box::from_raw(ptr)); } } }
        #[no_mangle]
        pub unsafe extern "C" fn #clone_fn_name(ptr: *const #name) -> *mut #name {
            unsafe { Box::into_raw(Box::new((*ptr).clone())) }
        }
        #getter_setter_fns
    }.into()
}

#[proc_macro_attribute]
pub fn jvm_impl(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut crate_name_opt: Option<String> = None;

    // 引数のパース #[jvm_impl(crate = "...")]
    let attr_parser = syn::meta::parser(|meta| {
        if meta.path.is_ident("crate") {
            let value = meta.value()?;
            let s: syn::LitStr = value.parse()?;
            crate_name_opt = Some(s.value());
            Ok(())
        } else {
            Err(meta.error("unsupported attribute"))
        }
    });
    parse_macro_input!(attr with attr_parser);

    let crate_name = crate_name_opt.expect("jvm_impl requires crate name");
    let mut ast = parse_macro_input!(item as ItemImpl);
    let self_ty = &ast.self_ty;
    let self_ty_str = quote!{ #self_ty }.to_string().replace(" ", "");
    let ffi_prefix = generate_ffi_prefix(&crate_name, &self_ty_str);

    let mut generated_fns = quote! {};
    let mut method_metadata_list = Vec::new();

    for item in &mut ast.items {
        if let ImplItem::Fn(method) = item {
            if let Visibility::Public(_) = &method.vis {
                let method_name = &method.sig.ident;
                let ffi_method_name = format_ident!("{}_impl_{}", ffi_prefix, method_name);

                method_metadata_list.push(MethodMetadata {
                    name: method_name.to_string(),
                    ffi_name: ffi_method_name.to_string(),
                    args: vec![], // 簡易化
                    return_type: "()".to_string(),
                    has_self: method.sig.receiver().is_some(),
                    is_static: method.sig.receiver().is_none(),
                });

                generated_fns.extend(quote! {
                    #[no_mangle]
                    pub unsafe extern "C" fn #ffi_method_name() {
                        unimplemented!("Method wrap not fully implemented");
                    }
                });
            }
        }
    }

    // メタデータ保存
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let path = PathBuf::from(out_dir).join(format!("{}_method_metadata.json", ffi_prefix));
        let _ = fs::write(path, serde_json::to_string_pretty(&method_metadata_list).unwrap());
    }

    quote! {
        #ast
        #generated_fns
    }.into()
}
