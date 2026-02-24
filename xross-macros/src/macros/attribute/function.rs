use crate::codegen::ffi::{
    MethodFfiData, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::parse::Parser;
use syn::{FnArg, ReturnType};
use xross_metadata::ThreadSafety;

pub fn impl_xross_raw_function_attribute(attr: TokenStream, input_fn: syn::ItemFn) -> TokenStream {
    impl_xross_function_attribute_ext(attr, input_fn, true)
}

pub fn impl_xross_function_attribute(attr: TokenStream, input_fn: syn::ItemFn) -> TokenStream {
    impl_xross_function_attribute_ext(attr, input_fn, false)
}

fn impl_xross_function_attribute_ext(
    attr: TokenStream,
    mut input_fn: syn::ItemFn,
    force_raw: bool,
) -> TokenStream {
    let mut package_name = String::new();
    let mut handle_mode = None;
    let mut safety = None;
    let mut is_raw = force_raw;
    let mut raw_input: Option<super::raw::RawAttrInput> = None;

    if is_raw {
        if let Ok(input) = syn::parse2::<super::raw::RawAttrInput>(attr.clone()) {
            if input.handle_mode != xross_metadata::HandleMode::Normal {
                handle_mode = Some(input.handle_mode);
            }
            raw_input = Some(input);
        }
    }

    input_fn.attrs.retain(|attr| {
        if attr.path().is_ident("xross_raw_function") {
            if !is_raw {
                is_raw = true;
                if let syn::Meta::List(list) = &attr.meta {
                    if let Ok(input) = syn::parse2::<super::raw::RawAttrInput>(list.tokens.clone())
                    {
                        if input.handle_mode != xross_metadata::HandleMode::Normal {
                            handle_mode = Some(input.handle_mode);
                        }
                        raw_input = Some(input);
                    }
                }
            }
            false
        } else {
            true
        }
    });

    if !attr.is_empty() {
        let res = syn::meta::parser(|meta| {
            if meta.path.is_ident("package") {
                let value = meta.value()?;
                if let Ok(lit) = value.parse::<syn::LitStr>() {
                    package_name = lit.value();
                } else if let Ok(id) = value.parse::<syn::Ident>() {
                    package_name = id.to_string();
                }
            } else if meta.path.is_ident("critical") {
                let allow_heap_access = crate::utils::parse_critical_nested(&meta)?;
                handle_mode = Some(xross_metadata::HandleMode::Critical { allow_heap_access });
            } else if meta.path.is_ident("panicable") {
                handle_mode = Some(xross_metadata::HandleMode::Panicable);
            } else if meta.path.is_ident("safety") {
                let value = meta.value()?.parse::<syn::Ident>()?;
                safety = match value.to_string().as_str() {
                    "Unsafe" => Some(ThreadSafety::Unsafe),
                    "Atomic" => Some(ThreadSafety::Atomic),
                    "Immutable" => Some(ThreadSafety::Immutable),
                    "Lock" => Some(ThreadSafety::Lock),
                    _ => None,
                };
            }
            Ok(())
        })
        .parse2(attr);
        if let Err(e) = res {
            panic!("Failed to parse xross_function attributes: {}", e);
        }
    }

    let rust_fn_name = &input_fn.sig.ident;
    let name_str = rust_fn_name.to_string();
    let is_async = input_fn.sig.asyncness.is_some();

    let symbol_prefix = crate::utils::get_symbol_prefix(&package_name);

    let mut ffi_data = MethodFfiData::new(&symbol_prefix, rust_fn_name);
    ffi_data.is_async = is_async;
    let dummy_ident = syn::Ident::new("Global", proc_macro2::Span::call_site());

    let mut extra_functions = Vec::new();
    let handle_mode = handle_mode.unwrap_or_else(|| extract_handle_mode(&input_fn.attrs));
    let safety = safety.unwrap_or_else(|| extract_safety_attr(&input_fn.attrs, ThreadSafety::Lock));
    let docs = extract_docs(&input_fn.attrs);

    if is_raw && let Some(raw) = &raw_input {
        for arg in &raw.sig_inputs {
            if let FnArg::Typed(pat_type) = arg {
                let arg_name = if let syn::Pat::Ident(id) = &*pat_type.pat {
                    id.ident.to_string()
                } else {
                    "arg".into()
                };
                let xross_ty = crate::types::resolver::resolve_type_with_attr_ext(
                    &pat_type.ty,
                    &[],
                    &package_name,
                    Some(&dummy_ident),
                    true, // force_value
                );
                ffi_data.args_meta.push(xross_metadata::XrossField {
                    name: arg_name,
                    ty: xross_ty,
                    safety: safety.clone(),
                    docs: vec![],
                });
                let arg_id = format_ident!("{}", ffi_data.args_meta.last().unwrap().name);
                let arg_ty = &pat_type.ty;
                ffi_data.c_args.push(quote!(#arg_id: #arg_ty));
            }
        }

        let ret_ty = crate::codegen::ffi::resolve_return_type_ext(
            &raw.sig_output,
            &[],
            &package_name,
            &dummy_ident,
            true, // force_value
        );

        crate::utils::register_xross_function_ext(
            &package_name,
            &name_str,
            &ffi_data,
            handle_mode,
            safety,
            &ret_ty,
            docs,
            true, // is_raw
        );

        let export_ident = &ffi_data.export_ident;
        let c_args = &ffi_data.c_args;
        let ffi_arg_names: Vec<_> = raw
            .sig_inputs
            .iter()
            .filter_map(|arg| {
                if let FnArg::Typed(pt) = arg {
                    if let syn::Pat::Ident(id) = &*pt.pat { Some(&id.ident) } else { None }
                } else {
                    None
                }
            })
            .collect();

        let import_logic = if let Some(import) = &raw.import_closure {
            quote! { let _rust_args = (#import)(#(#ffi_arg_names),*); }
        } else {
            if ffi_arg_names.len() > 1 {
                quote! { let _rust_args = (#(#ffi_arg_names),*); }
            } else if ffi_arg_names.len() == 1 {
                let arg = &ffi_arg_names[0];
                quote! { let _rust_args = #arg; }
            } else {
                quote! { let _rust_args = (); }
            }
        };

        let rust_args_count = input_fn.sig.inputs.len();
        let rust_call_args = if rust_args_count > 1 {
            let indices = (0..rust_args_count).map(syn::Index::from);
            quote!(#(_rust_args.#indices),*)
        } else if rust_args_count == 1 {
            quote!(_rust_args)
        } else {
            quote!()
        };

        let rust_call = quote!(#rust_fn_name(#rust_call_args));
        let export_logic = if let Some(export) = &raw.export_closure {
            quote! { (#export)(_rust_result) }
        } else {
            quote! { _rust_result }
        };

        let c_ret_ty = match &raw.sig_output {
            ReturnType::Default => quote!(()),
            ReturnType::Type(_, ty) => quote!(#ty),
        };

        let wrapper_body = if handle_mode == xross_metadata::HandleMode::Panicable {
            let error_arm = crate::codegen::ffi::gen_panic_error_arm("raw function");
            quote! {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                    #import_logic
                    let _rust_result = #rust_call;
                    #export_logic
                }));
                match result {
                    Ok(val) => xross_core::XrossResult { is_ok: true, ptr: val as *mut std::ffi::c_void },
                    #error_arm
                }
            }
        } else {
            quote! {
                #import_logic
                let _rust_result = #rust_call;
                #export_logic
            }
        };

        if handle_mode == xross_metadata::HandleMode::Panicable {
            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #export_ident(out: *mut xross_core::XrossResult, #(#c_args),*) {
                    let res = { #wrapper_body };
                    unsafe { std::ptr::write(out, res) };
                }
            });
        } else {
            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_ty {
                    #wrapper_body
                }
            });
        }

        return quote! { #(#extra_functions)* #input_fn };
    }

    process_method_args(&input_fn.sig.inputs, &package_name, &dummy_ident, &mut ffi_data);

    let ret_ty =
        resolve_return_type(&input_fn.sig.output, &input_fn.attrs, &package_name, &dummy_ident);

    crate::utils::register_xross_function(
        &package_name,
        &name_str,
        &ffi_data,
        handle_mode,
        safety,
        &ret_ty,
        docs,
    );

    let call_args = &ffi_data.call_args;
    let inner_call = quote! { #rust_fn_name(#(#call_args),*) };
    write_ffi_function(
        &ffi_data,
        &ret_ty,
        &input_fn.sig.output,
        inner_call,
        handle_mode,
        &mut extra_functions,
    );

    quote! { #(#extra_functions)* #input_fn }
}
