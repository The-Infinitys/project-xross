mod type_mapping;
mod utils;

use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{FnArg, ImplItem, ItemImpl, Pat, ReturnType, Type, parse_macro_input};
use utils::*;
use xross_metadata::{
    ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossMethodType,
    XrossStruct, XrossType, XrossVariant,
};

#[proc_macro_derive(JvmClass, attributes(jvm_field, jvm_package, xross))]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as syn::Item);

    // 実行時(コンパイル時)にマクロ呼び出し側のクレート名を取得
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let mut extra_functions = Vec::new();

    match input {
        syn::Item::Struct(s) => {
            let name = &s.ident;
            let package = extract_package(&s.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            // 1. レイアウト文字列生成ロジック (utils.rs の関数を使用)
            let layout_logic = generate_struct_layout(&s);

            // 2. メタデータ収集と保存
            let mut fields = Vec::new();
            if let syn::Fields::Named(f) = &s.fields {
                for field in &f.named {
                    if field.attrs.iter().any(|a| a.path().is_ident("jvm_field")) {
                        fields.push(XrossField {
                            name: field.ident.as_ref().unwrap().to_string(),
                            ty: resolve_type_with_attr(
                                &field.ty,
                                &field.attrs,
                                &package,
                                Some(name),
                                false,
                            ),
                            safety: extract_safety_attr(&field.attrs, ThreadSafety::Lock),
                            docs: extract_docs(&field.attrs),
                        });
                    }
                }
            }

            save_definition(
                name,
                &XrossDefinition::Struct(XrossStruct {
                    signature: if package.is_empty() {
                        name.to_string()
                    } else {
                        format!("{}.{}", package, name)
                    },
                    symbol_prefix: symbol_base.clone(),
                    package_name: package,
                    name: name.to_string(),
                    fields,
                    methods: vec![],
                    docs: extract_docs(&s.attrs),
                }),
            );

            // 3. 共通FFI (Drop, Clone, Layout) の生成
            generate_common_ffi(name, &symbol_base, layout_logic, &mut extra_functions);
        }

        syn::Item::Enum(e) => {
            let name = &e.ident;
            let package = extract_package(&e.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            // 1. レイアウト文字列生成ロジック (utils.rs の関数を使用)
            let layout_logic = generate_enum_layout(&e);

            // 2. メタデータ収集と保存
            let mut variants = Vec::new();
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
                        .unwrap_or_else(|| i.to_string());
                    let ty = resolve_type_with_attr(
                        &field.ty,
                        &field.attrs,
                        &package,
                        Some(name),
                        false,
                    );

                    v_fields.push(XrossField {
                        name: field_name,
                        ty: ty.clone(),
                        safety: ThreadSafety::Lock,
                        docs: extract_docs(&field.attrs),
                    });

                    let arg_id = format_ident!("arg_{}", i);
                    let raw_ty = &field.ty;

                    if matches!(
                        ty,
                        XrossType::RustStruct { .. }
                            | XrossType::RustEnum { .. }
                            | XrossType::Object { .. }
                    ) {
                        c_param_defs.push(quote! { #arg_id: *mut std::ffi::c_void });
                        internal_conversions.push(
                            quote! { let #arg_id = *Box::from_raw(#arg_id as *mut #raw_ty); },
                        );
                    } else {
                        c_param_defs.push(quote! { #arg_id: #raw_ty });
                    }

                    if let Some(id) = &field.ident {
                        call_args.push(quote! { #id: #arg_id });
                    } else {
                        call_args.push(quote! { #arg_id });
                    }
                }

                let enum_construct = if v.fields.is_empty() {
                    quote! { #name::#v_ident }
                } else if matches!(v.fields, syn::Fields::Named(_)) {
                    quote! { #name::#v_ident { #(#call_args),* } }
                } else {
                    quote! { #name::#v_ident(#(#call_args),*) }
                };

                // バリアントごとのコンストラクタ FFI
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

            save_definition(
                name,
                &XrossDefinition::Enum(XrossEnum {
                    signature: if package.is_empty() {
                        name.to_string()
                    } else {
                        format!("{}.{}", package, name)
                    },
                    symbol_prefix: symbol_base.clone(),
                    package_name: package,
                    name: name.to_string(),
                    variants,
                    methods: vec![],
                    docs: extract_docs(&e.attrs),
                }),
            );

            // 3. 共通FFI
            generate_common_ffi(name, &symbol_base, layout_logic, &mut extra_functions);

            // 4. Enum タグ取得 FFI
            let tag_fn_id = format_ident!("{}_get_tag", symbol_base);
            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #tag_fn_id(ptr: *const #name) -> i32 {
                    if ptr.is_null() { return -1; }
                    // Rust Enum の判別子は先頭にあることを期待(repr(C, i32)等が推奨)
                    *(ptr as *const i32)
                }
            });
        }
        _ => panic!("#[derive(JvmClass)] only supports Struct and Enum"),
    }

    quote!(#(#extra_functions)*).into()
}

#[proc_macro_attribute]
pub fn jvm_class(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input_impl = parse_macro_input!(item as ItemImpl);
    let type_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("jvm_class must be used on a direct type implementation");
    };

    let mut definition = load_definition(type_name_ident)
        .expect("JvmClass definition not found. Apply #[derive(JvmClass)] first.");

    let (package_name, symbol_base, is_struct) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone(), true),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone(), false),
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_method = false;
            method.attrs.retain(|attr| {
                if attr.path().is_ident("jvm_new") {
                    is_new = true;
                    false
                } else if attr.path().is_ident("jvm_method") {
                    is_method = true;
                    false
                } else {
                    true
                }
            });

            if !is_new && !is_method {
                continue;
            }

            let rust_fn_name = &method.sig.ident;
            let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
            let export_ident = format_ident!("{}", symbol_name);

            let mut method_type = XrossMethodType::Static;
            let mut args_meta = Vec::new();
            let mut c_args = Vec::new();
            let mut call_args = Vec::new();
            let mut conversion_logic = Vec::new();

            for input in &method.sig.inputs {
                match input {
                    FnArg::Receiver(receiver) => {
                        let arg_ident = format_ident!("_self");
                        if receiver.reference.is_none() {
                            method_type = XrossMethodType::OwnedInstance;
                            c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                            call_args.push(
                                quote! { *Box::from_raw(#arg_ident as *mut #type_name_ident) },
                            );
                        } else {
                            method_type = if receiver.mutability.is_some() {
                                XrossMethodType::MutInstance
                            } else {
                                XrossMethodType::ConstInstance
                            };
                            c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                            call_args.push(if receiver.mutability.is_some() {
                                quote!(&mut *(#arg_ident as *mut #type_name_ident))
                            } else {
                                quote!(&*(#arg_ident as *const #type_name_ident))
                            });
                        }
                    }
                    FnArg::Typed(pat_type) => {
                        let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                            id.ident.to_string()
                        } else {
                            "arg".into()
                        };
                        let arg_ident = format_ident!("{}", arg_name);
                        let xross_ty = resolve_type_with_attr(
                            &pat_type.ty,
                            &pat_type.attrs,
                            &package_name,
                            Some(type_name_ident),
                            true,
                        );

                        args_meta.push(XrossField {
                            name: arg_name.clone(),
                            ty: xross_ty.clone(),
                            safety: extract_safety_attr(&pat_type.attrs, ThreadSafety::Lock),
                            docs: vec![],
                        });

                        match xross_ty {
                            XrossType::String => {
                                let raw_name = format_ident!("{}_raw", arg_ident);
                                c_args.push(quote! { #raw_name: *const std::ffi::c_char });
                                conversion_logic.push(quote! {
                                    let #arg_ident = unsafe {
                                        if #raw_name.is_null() { "" }
                                        else { std::ffi::CStr::from_ptr(#raw_name).to_str().unwrap_or("") }
                                    };
                                });
                                call_args.push(quote! { #arg_ident });
                            }
                            XrossType::RustStruct { .. }
                            | XrossType::RustEnum { .. }
                            | XrossType::Object { .. } => {
                                let raw_ty = &pat_type.ty;
                                c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                                // 値渡し or 参照渡しをシグネチャから判断して deref する
                                if let Type::Reference(_) = &*pat_type.ty {
                                    call_args.push(quote! { &*(#arg_ident as *mut #raw_ty) });
                                } else {
                                    call_args.push(
                                        quote! { *Box::from_raw(#arg_ident as *mut #raw_ty) },
                                    );
                                }
                            }
                            _ => {
                                let rust_type_token = &pat_type.ty;
                                c_args.push(quote! { #arg_ident: #rust_type_token });
                                call_args.push(quote! { #arg_ident });
                            }
                        }
                    }
                }
            }

            let ret_ty = if is_new {
                let sig = if package_name.is_empty() {
                    type_name_ident.to_string()
                } else {
                    format!("{}.{}", package_name, type_name_ident)
                };
                if is_struct {
                    XrossType::RustStruct { signature: sig }
                } else {
                    XrossType::RustEnum { signature: sig }
                }
            } else {
                match &method.sig.output {
                    ReturnType::Default => XrossType::Void,
                    ReturnType::Type(_, ty) => resolve_type_with_attr(
                        ty,
                        &method.attrs,
                        &package_name,
                        Some(type_name_ident),
                        true,
                    ),
                }
            };

            methods_meta.push(XrossMethod {
                name: rust_fn_name.to_string(),
                symbol: symbol_name.clone(),
                method_type,
                safety: extract_safety_attr(&method.attrs, ThreadSafety::Lock),
                is_constructor: is_new,
                args: args_meta,
                ret: ret_ty.clone(),
                docs: extract_docs(&method.attrs),
            });

            let inner_call = quote! { #type_name_ident::#rust_fn_name(#(#call_args),*) };
            let (c_ret_type, wrapper_body) = match &ret_ty {
                XrossType::Void => (quote! { () }, quote! { #inner_call; }),
                XrossType::String => (
                    quote! { *mut std::ffi::c_char },
                    quote! { std::ffi::CString::new(#inner_call).unwrap_or_default().into_raw() },
                ),
                XrossType::RustStruct { .. }
                | XrossType::RustEnum { .. }
                | XrossType::Object { .. } => {
                    let is_reference = if let ReturnType::Type(_, ty) = &method.sig.output {
                        matches!(**ty, Type::Reference(_))
                    } else {
                        false
                    };

                    if is_reference {
                        // 参照を返す場合は、すでに有効なメモリを指しているため Box 化せずそのままキャスト
                        (
                            quote! { *mut std::ffi::c_void },
                            quote! { #inner_call as *const _ as *mut std::ffi::c_void },
                        )
                    } else {
                        // 値（所有権）を返す場合は、ヒープに固定してポインタを渡す
                        (
                            quote! { *mut std::ffi::c_void },
                            quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
                        )
                    }
                }
                _ => {
                    let raw_ret = if let ReturnType::Type(_, ty) = &method.sig.output {
                        quote! { #ty }
                    } else {
                        quote! { () }
                    };
                    (raw_ret, quote! { #inner_call })
                }
            };
            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_type {
                    #(#conversion_logic)*
                    #wrapper_body
                }
            });
        }
    }

    match &mut definition {
        XrossDefinition::Struct(s) => s.methods = methods_meta,
        XrossDefinition::Enum(e) => e.methods = methods_meta,
    }

    save_definition(type_name_ident, &definition);
    quote! { #input_impl #(#extra_functions)* }.into()
}
