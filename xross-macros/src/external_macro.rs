use crate::metadata::{load_definition, save_definition};
use crate::type_resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::parse::{Parse, ParseStream};
use syn::{FnArg, Pat, ReturnType, Signature, Token, Type, parse_macro_input};
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossField, XrossMethod, XrossMethodType,
    XrossOpaque, XrossType,
};

struct ExternalClassInput {
    package: String,
    class_name: syn::Ident,
    is_clonable: bool,
}

impl Parse for ExternalClassInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let package = input.parse::<syn::LitStr>()?.value();
        input.parse::<Token![,]>()?;
        let class_name = input.parse::<syn::Ident>()?;
        let is_clonable = if input.peek(Token![,]) {
            input.parse::<Token![,]>()?;
            input.parse::<syn::LitBool>()?.value
        } else {
            true
        };
        Ok(ExternalClassInput { package, class_name, is_clonable })
    }
}

pub fn impl_external_class(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as ExternalClassInput);
    let name_str = input.class_name.to_string();
    let name_ident = &input.class_name;
    let package = &input.package;

    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let symbol_base = build_symbol_base(&crate_name, package, &name_str);

    let mut methods = vec![];
    if input.is_clonable {
        methods.push(XrossMethod {
            name: "clone".to_string(),
            symbol: format!("{}_clone", symbol_base),
            method_type: XrossMethodType::ConstInstance,
            is_constructor: false,
            args: vec![],
            ret: XrossType::Object {
                signature: if package.is_empty() {
                    name_str.clone()
                } else {
                    format!("{}.{}", package, name_str)
                },
                ownership: Ownership::Owned,
            },
            safety: ThreadSafety::Lock,
            docs: vec!["Creates a clone of the native object.".to_string()],
        });
    }

    let definition = XrossDefinition::Opaque(XrossOpaque {
        signature: if package.is_empty() {
            name_str.clone()
        } else {
            format!("{}.{}", package, name_str)
        },
        symbol_prefix: symbol_base.clone(),
        package_name: package.clone(),
        name: name_str.clone(),
        fields: vec![],
        methods,
        docs: vec![format!("External wrapper for {}", name_str)],
        is_clonable: input.is_clonable,
        is_copy: false,
    });
    save_definition(&definition);

    let drop_fn = format_ident!("{}_drop", symbol_base);
    let size_fn = format_ident!("{}_size", symbol_base);

    let clone_ffi = if input.is_clonable {
        let clone_fn = format_ident!("{}_clone", symbol_base);
        quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #clone_fn(ptr: *const #name_ident) -> *mut #name_ident {
                if ptr.is_null() { return std::ptr::null_mut(); }
                let val_on_stack: #name_ident = std::ptr::read_unaligned(ptr);
                let cloned_val = val_on_stack.clone();
                std::mem::forget(val_on_stack);
                Box::into_raw(Box::new(cloned_val))
            }
        }
    } else {
        quote! {}
    };

    let gen_code = quote! {
        #clone_ffi

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_fn(ptr: *mut #name_ident) {
            if !ptr.is_null() {
                let _ = Box::from_raw(ptr);
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #size_fn() -> usize {
            std::mem::size_of::<#name_ident>()
        }
    };
    gen_code.into()
}

struct DottedPath {
    pub segments: Vec<syn::Ident>,
}

impl Parse for DottedPath {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let mut segments = Vec::new();
        segments.push(input.parse::<syn::Ident>()?);
        while input.peek(Token![.]) {
            input.parse::<Token![.]>()?;
            segments.push(input.parse::<syn::Ident>()?);
        }
        Ok(DottedPath { segments })
    }
}

struct ExternalMethodInput {
    class_path: DottedPath,
    sig: Signature,
}

impl Parse for ExternalMethodInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let class_path = input.parse::<DottedPath>()?;
        input.parse::<Token![,]>()?;

        // If it doesn't start with 'fn', we wrap it in 'fn' for parsing
        let sig = if input.peek(Token![fn]) {
            input.parse::<Signature>()?
        } else {
            // Manual parsing or just tell the user to use 'fn'?
            // Let's try to parse manually: name, then parens
            let ident = input.parse::<syn::Ident>()?;
            let content;
            syn::parenthesized!(content in input);
            let inputs = content.parse_terminated(FnArg::parse, Token![,])?;
            let output = input.parse::<ReturnType>()?;
            Signature {
                constness: None,
                asyncness: None,
                unsafety: None,
                abi: None,
                fn_token: <Token![fn]>::default(),
                ident,
                generics: syn::Generics::default(),
                paren_token: syn::token::Paren::default(),
                inputs,
                variadic: None,
                output,
            }
        };

        Ok(ExternalMethodInput { class_path, sig })
    }
}

pub fn impl_external_method(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as ExternalMethodInput);
    let class_ident = input.class_path.segments.last().unwrap();

    let mut definition = load_definition(class_ident)
        .expect("External class definition not found. Use external_class! first.");

    let (package_name, symbol_base) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone()),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone()),
        XrossDefinition::Opaque(o) => (o.package_name.clone(), o.symbol_prefix.clone()),
    };

    let rust_fn_name = &input.sig.ident;
    let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
    let export_ident = format_ident!("{}", symbol_name);

    let mut method_type = XrossMethodType::Static;
    let mut args_meta = Vec::new();
    let mut c_args = Vec::new();
    let mut call_args = Vec::new();
    let mut conversion_logic = Vec::new();

    for input_arg in &input.sig.inputs {
        match input_arg {
            FnArg::Receiver(receiver) => {
                let arg_ident = format_ident!("_self");
                if receiver.reference.is_none() {
                    method_type = XrossMethodType::OwnedInstance;
                    c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                    call_args.push(quote! { *Box::from_raw(#arg_ident as *mut #class_ident) });
                } else {
                    method_type = if receiver.mutability.is_some() {
                        XrossMethodType::MutInstance
                    } else {
                        XrossMethodType::ConstInstance
                    };
                    c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                    call_args.push(if receiver.mutability.is_some() {
                        quote!(&mut *(#arg_ident as *mut #class_ident))
                    } else {
                        quote!(&*(#arg_ident as *const #class_ident))
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
                    Some(class_ident),
                );

                args_meta.push(XrossField {
                    name: arg_name.clone(),
                    ty: xross_ty.clone(),
                    safety: ThreadSafety::Lock,
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
                        let is_string_owned = if let Type::Path(p) = &*pat_type.ty {
                            p.path.is_ident("String")
                        } else {
                            false
                        };
                        if is_string_owned {
                            call_args.push(quote! { #arg_ident.to_string() });
                        } else {
                            call_args.push(quote! { #arg_ident });
                        }
                    }
                    XrossType::Object { .. } => {
                        let raw_ty = &pat_type.ty;
                        c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                        if let Type::Reference(_) = &*pat_type.ty {
                            call_args.push(quote! { &*(#arg_ident as *mut #raw_ty) });
                        } else {
                            call_args.push(quote! { *Box::from_raw(#arg_ident as *mut #raw_ty) });
                        }
                    }
                    XrossType::Option(_inner) => {
                        let raw_ty = &pat_type.ty;
                        c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                        let inner_rust_ty = extract_inner_type(raw_ty);
                        conversion_logic.push(quote! {
                            let #arg_ident = if #arg_ident.is_null() {
                                None
                            } else {
                                Some(*Box::from_raw(#arg_ident as *mut #inner_rust_ty))
                            };
                        });
                        call_args.push(quote! { #arg_ident });
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

    let ret_ty = match &input.sig.output {
        ReturnType::Default => XrossType::Void,
        ReturnType::Type(_, ty) => {
            let mut xross_ty = resolve_type_with_attr(ty, &[], &package_name, Some(class_ident));
            let ownership = match &**ty {
                Type::Reference(r) => {
                    if r.mutability.is_some() {
                        Ownership::MutRef
                    } else {
                        Ownership::Ref
                    }
                }
                _ => Ownership::Owned,
            };
            if let XrossType::Object { ownership: o, .. } = &mut xross_ty {
                *o = ownership;
            }
            xross_ty
        }
    };

    let method_meta = XrossMethod {
        name: rust_fn_name.to_string(),
        symbol: symbol_name.clone(),
        method_type,
        safety: ThreadSafety::Lock,
        is_constructor: false,
        args: args_meta,
        ret: ret_ty.clone(),
        docs: vec![],
    };

    match &mut definition {
        XrossDefinition::Struct(s) => s.methods.push(method_meta),
        XrossDefinition::Enum(e) => e.methods.push(method_meta),
        XrossDefinition::Opaque(o) => o.methods.push(method_meta),
    }
    save_definition(&definition);

    let inner_call = quote! { #class_ident::#rust_fn_name(#(#call_args),*) };
    let (c_ret_type, wrapper_body) = match &ret_ty {
        XrossType::Void => (quote! { () }, quote! { #inner_call; }),
        XrossType::String => (
            quote! { *mut std::ffi::c_char },
            quote! { std::ffi::CString::new(#inner_call).unwrap_or_default().into_raw() },
        ),
        XrossType::Object { ownership, .. } => match ownership {
            Ownership::Ref | Ownership::MutRef => (
                quote! { *mut std::ffi::c_void },
                quote! { #inner_call as *const _ as *mut std::ffi::c_void },
            ),
            _ => (
                quote! { *mut std::ffi::c_void },
                quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
            ),
        },
        XrossType::Option(inner) => match &**inner {
            XrossType::String => (
                quote! { *mut std::ffi::c_char },
                quote! {
                    match #inner_call {
                        Some(s) => std::ffi::CString::new(s).unwrap_or_default().into_raw(),
                        None => std::ptr::null_mut(),
                    }
                },
            ),
            _ => (
                quote! { *mut std::ffi::c_void },
                quote! {
                    match #inner_call {
                        Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                        None => std::ptr::null_mut(),
                    }
                },
            ),
        },
        XrossType::Result { ok, err } => {
            let gen_ptr = |ty: &XrossType, val_ident: TokenStream| match ty {
                XrossType::String => quote! {
                    std::ffi::CString::new(#val_ident).unwrap_or_default().into_raw() as *mut std::ffi::c_void
                },
                _ => quote! {
                    Box::into_raw(Box::new(#val_ident)) as *mut std::ffi::c_void
                },
            };
            let ok_ptr_logic = gen_ptr(ok, quote! { val });
            let err_ptr_logic = gen_ptr(err, quote! { e });

            (
                quote! { xross_core::XrossResult },
                quote! {
                    match #inner_call {
                        Ok(val) => xross_core::XrossResult {
                            is_ok: true,
                            ptr: #ok_ptr_logic,
                        },
                        Err(e) => xross_core::XrossResult {
                            is_ok: false,
                            ptr: #err_ptr_logic,
                        },
                    }
                },
            )
        }
        _ => {
            let rust_type_token = match &input.sig.output {
                ReturnType::Type(_, ty) => quote! { #ty },
                _ => quote! { () },
            };
            (rust_type_token, quote! { #inner_call })
        }
    };

    let gen_code = quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_type {
            #(#conversion_logic)*
            #wrapper_body
        }
    };
    gen_code.into()
}

pub fn impl_external_new(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as ExternalMethodInput);
    let class_ident = input.class_path.segments.last().unwrap();

    let mut definition = load_definition(class_ident)
        .expect("External class definition not found. Use external_class! first.");

    let (package_name, symbol_base) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone()),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone()),
        XrossDefinition::Opaque(o) => (o.package_name.clone(), o.symbol_prefix.clone()),
    };

    let rust_fn_name = &input.sig.ident;
    let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
    let export_ident = format_ident!("{}", symbol_name);

    let mut args_meta = Vec::new();
    let mut c_args = Vec::new();
    let mut call_args = Vec::new();
    let mut conversion_logic = Vec::new();

    for input_arg in &input.sig.inputs {
        if let FnArg::Typed(pat_type) = input_arg {
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
                Some(class_ident),
            );

            args_meta.push(XrossField {
                name: arg_name.clone(),
                ty: xross_ty.clone(),
                safety: ThreadSafety::Lock,
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
                    call_args.push(quote! { #arg_ident.to_string() });
                }
                XrossType::Object { .. } => {
                    let raw_ty = &pat_type.ty;
                    c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                    call_args.push(quote! { *Box::from_raw(#arg_ident as *mut #raw_ty) });
                }
                XrossType::Option(_inner) => {
                    let raw_ty = &pat_type.ty;
                    c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                    let inner_rust_ty = extract_inner_type(raw_ty);
                    conversion_logic.push(quote! {
                        let #arg_ident = if #arg_ident.is_null() {
                            None
                        } else {
                            Some(*Box::from_raw(#arg_ident as *mut #inner_rust_ty))
                        };
                    });
                    call_args.push(quote! { #arg_ident });
                }
                _ => {
                    let rust_type_token = &pat_type.ty;
                    c_args.push(quote! { #arg_ident: #rust_type_token });
                    call_args.push(quote! { #arg_ident });
                }
            }
        }
    }

    let method_meta = XrossMethod {
        name: rust_fn_name.to_string(),
        symbol: symbol_name.clone(),
        method_type: XrossMethodType::Static,
        safety: ThreadSafety::Lock,
        is_constructor: true,
        args: args_meta,
        ret: XrossType::Object {
            signature: if package_name.is_empty() {
                class_ident.to_string()
            } else {
                format!("{}.{}", package_name, class_ident)
            },
            ownership: Ownership::Owned,
        },
        docs: vec![],
    };

    match &mut definition {
        XrossDefinition::Struct(s) => s.methods.push(method_meta),
        XrossDefinition::Enum(e) => e.methods.push(method_meta),
        XrossDefinition::Opaque(o) => o.methods.push(method_meta),
    }
    save_definition(&definition);

    let gen_code = quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> *mut #class_ident {
            #(#conversion_logic)*
            Box::into_raw(Box::new(#class_ident::#rust_fn_name(#(#call_args),*)))
        }
    };
    gen_code.into()
}

struct ExternalFieldInput {
    class_path: DottedPath,
    field_name: syn::Ident,
    field_ty: Type,
}

impl Parse for ExternalFieldInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let class_path = input.parse::<DottedPath>()?;
        input.parse::<Token![,]>()?;
        let field_name = input.parse::<syn::Ident>()?;
        input.parse::<Token![:]>()?;
        let field_ty = input.parse::<Type>()?;
        Ok(ExternalFieldInput { class_path, field_name, field_ty })
    }
}

pub fn impl_external_field(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as ExternalFieldInput);
    let class_ident = input.class_path.segments.last().unwrap();

    let mut definition = load_definition(class_ident)
        .expect("External class definition not found. Use external_class! first.");

    let (package_name, symbol_base) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone()),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone()),
        XrossDefinition::Opaque(o) => (o.package_name.clone(), o.symbol_prefix.clone()),
    };

    let field_name_str = input.field_name.to_string();
    let xross_ty = resolve_type_with_attr(&input.field_ty, &[], &package_name, Some(class_ident));

    let field_meta = XrossField {
        name: field_name_str.clone(),
        ty: xross_ty.clone(),
        safety: ThreadSafety::Lock,
        docs: vec![],
    };

    match &mut definition {
        XrossDefinition::Struct(s) => s.fields.push(field_meta),
        XrossDefinition::Enum(_) => panic!("Enum fields are not supported via external_field!"),
        XrossDefinition::Opaque(o) => o.fields.push(field_meta),
    }
    save_definition(&definition);

    let field_ident = &input.field_name;
    let mut extra_functions = Vec::new();

    match &xross_ty {
        XrossType::String => {
            let get_fn = format_ident!("{}_property_{}_str_get", symbol_base, field_name_str);
            let set_fn = format_ident!("{}_property_{}_str_set", symbol_base, field_name_str);
            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #class_ident) -> *mut std::ffi::c_char {
                    if ptr.is_null() { return std::ptr::null_mut(); }
                    let obj = &*ptr;
                    std::ffi::CString::new(obj.#field_ident.as_str()).unwrap().into_raw()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #class_ident, val: *const std::ffi::c_char) {
                    if ptr.is_null() || val.is_null() { return; }
                    let obj = &mut *ptr;
                    let s = std::ffi::CStr::from_ptr(val).to_string_lossy().into_owned();
                    obj.#field_ident = s;
                }
            });
        }
        XrossType::Option(inner_xross) => {
            let get_fn = format_ident!("{}_property_{}_opt_get", symbol_base, field_name_str);
            let set_fn = format_ident!("{}_property_{}_opt_set", symbol_base, field_name_str);
            let inner_rust_ty = extract_inner_type(&input.field_ty);

            let get_val_logic = if matches!(**inner_xross, XrossType::String) {
                quote! { std::ffi::CString::new(v.as_str()).unwrap().into_raw() as *mut std::ffi::c_void }
            } else {
                quote! { Box::into_raw(Box::new(v.clone())) as *mut std::ffi::c_void }
            };

            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #class_ident) -> *mut std::ffi::c_void {
                    if ptr.is_null() { return std::ptr::null_mut(); }
                    let obj = &*ptr;
                    match &obj.#field_ident {
                        Some(v) => #get_val_logic,
                        None => std::ptr::null_mut(),
                    }
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #class_ident, val: *mut std::ffi::c_void) {
                    if ptr.is_null() { return; }
                    let obj = &mut *ptr;
                    obj.#field_ident = if val.is_null() {
                        None
                    } else {
                        let v = &*(val as *const #inner_rust_ty);
                        Some(v.clone())
                    };
                }
            });
        }
        XrossType::Result { ok: ok_xross, err: err_xross } => {
            let get_fn = format_ident!("{}_property_{}_res_get", symbol_base, field_name_str);
            let set_fn = format_ident!("{}_property_{}_res_set", symbol_base, field_name_str);
            let inner_ok_ty = extract_inner_type_from_res(&input.field_ty, true);
            let inner_err_ty = extract_inner_type_from_res(&input.field_ty, false);

            let gen_ptr = |ty: &XrossType, val_expr: TokenStream| match ty {
                XrossType::String => quote! {
                    std::ffi::CString::new(#val_expr.as_str()).unwrap().into_raw() as *mut std::ffi::c_void
                },
                _ => quote! {
                    Box::into_raw(Box::new(#val_expr.clone())) as *mut std::ffi::c_void
                },
            };
            let ok_ptr_logic = gen_ptr(ok_xross, quote! { v });
            let err_ptr_logic = gen_ptr(err_xross, quote! { e });

            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #class_ident) -> xross_core::XrossResult {
                    if ptr.is_null() { return xross_core::XrossResult { is_ok: false, ptr: std::ptr::null_mut() }; }
                    let obj = &*ptr;
                    match &obj.#field_ident {
                        Ok(v) => xross_core::XrossResult {
                            is_ok: true,
                            ptr: #ok_ptr_logic,
                        },
                        Err(e) => xross_core::XrossResult {
                            is_ok: false,
                            ptr: #err_ptr_logic,
                        },
                    }
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #class_ident, val: xross_core::XrossResult) {
                    if ptr.is_null() { return; }
                    let obj = &mut *ptr;
                    obj.#field_ident = if val.is_ok {
                        let v = &*(val.ptr as *const #inner_ok_ty);
                        Ok(v.clone())
                    } else {
                        let e = &*(val.ptr as *const #inner_err_ty);
                        Err(e.clone())
                    };
                }
            });
        }
        _ => {
            // For primitive types, we can use offset-based access in Kotlin if it's a normal struct,
            // but for Opaque types we should probably generate FFI anyway if the layout is unknown.
            // But if it's external_field!, we assume it's a public field of a known Rust struct.
            let get_fn = format_ident!("{}_property_{}_get", symbol_base, field_name_str);
            let set_fn = format_ident!("{}_property_{}_set", symbol_base, field_name_str);
            let rust_ty = &input.field_ty;

            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #class_ident) -> #rust_ty {
                    if ptr.is_null() { panic!("NULL pointer in property get"); }
                    (*ptr).#field_ident.clone()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #class_ident, val: #rust_ty) {
                    if ptr.is_null() { panic!("NULL pointer in property set"); }
                    (*ptr).#field_ident = val;
                }
            });
        }
    }

    let gen_code = quote! { #(#extra_functions)* };
    gen_code.into()
}
