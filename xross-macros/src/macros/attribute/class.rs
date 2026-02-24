use crate::codegen::ffi::{
    MethodFfiData, build_signature, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::metadata::{load_definition, save_definition};
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{FnArg, ImplItem, ItemImpl, ReturnType, Type};
use xross_metadata::{Ownership, ThreadSafety, XrossDefinition, XrossMethod};

pub fn impl_xross_class_attribute(_attr: TokenStream, mut input_impl: ItemImpl) -> TokenStream {
    let type_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("xross_methods must be used on a direct type implementation");
    };

    let mut definition = load_definition(type_name_ident).expect(
        "XrossClass definition not found. Apply #[derive(XrossClass)] or xross_class! first.",
    );

    let (package_name, symbol_base) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone()),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone()),
        XrossDefinition::Opaque(o) => (o.package_name.clone(), o.symbol_prefix.clone()),
        XrossDefinition::Function(f) => (f.package_name.clone(), f.symbol.clone()),
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_default = false;
            let mut is_method = false;
            let mut is_raw = false;
            let mut raw_input: Option<super::raw::RawAttrInput> = None;

            let mut handle_mode = extract_handle_mode(&method.attrs);

            method.attrs.retain(|attr| {
                if attr.path().is_ident("xross_new") {
                    is_new = true;
                    false
                } else if attr.path().is_ident("xross_default") {
                    is_default = true;
                    is_new = true;
                    false
                } else if attr.path().is_ident("xross_method") {
                    is_method = true;
                    false
                } else if attr.path().is_ident("xross_raw_method") {
                    is_raw = true;
                    if let syn::Meta::List(list) = &attr.meta {
                        if let Ok(input) =
                            syn::parse2::<super::raw::RawAttrInput>(list.tokens.clone())
                        {
                            if input.handle_mode != xross_metadata::HandleMode::Normal {
                                handle_mode = input.handle_mode;
                            }
                            raw_input = Some(input);
                        }
                    }
                    false
                } else {
                    true
                }
            });

            if !is_new && !is_method && !is_raw {
                continue;
            }

            let rust_fn_name = &method.sig.ident;
            let is_async = method.sig.asyncness.is_some();
            let mut ffi_data = MethodFfiData::new(&symbol_base, rust_fn_name);
            ffi_data.is_async = is_async;

            if is_raw && let Some(raw) = &raw_input {
                // For raw methods, we use the signature from the attribute for metadata
                // But we still need to know the receiver type
                if let Some(FnArg::Receiver(receiver)) = method.sig.inputs.first() {
                    let (m_ty, c_arg, call_arg) =
                        crate::codegen::ffi::gen_receiver_logic(receiver, type_name_ident);
                    ffi_data.method_type = m_ty;
                    ffi_data.c_args.push(c_arg);
                    ffi_data.call_args.push(call_arg);
                }

                for arg in &raw.sig_inputs {
                    if let FnArg::Typed(pat_type) = arg {
                        let arg_name = if let syn::Pat::Ident(id) = &*pat_type.pat {
                            id.ident.to_string()
                        } else {
                            "arg".into()
                        };
                        let xross_ty = crate::types::resolver::resolve_type_with_attr(
                            &pat_type.ty,
                            &[],
                            &package_name,
                            Some(type_name_ident),
                        );
                        ffi_data.args_meta.push(xross_metadata::XrossField {
                            name: arg_name,
                            ty: xross_ty,
                            safety: extract_safety_attr(&method.attrs, ThreadSafety::Lock),
                            docs: vec![],
                        });
                        let arg_id = format_ident!("{}", ffi_data.args_meta.last().unwrap().name);
                        let arg_ty = &pat_type.ty;
                        ffi_data.c_args.push(quote!(#arg_id: #arg_ty));
                    }
                }

                let ret_ty =
                    resolve_return_type(&raw.sig_output, &[], &package_name, type_name_ident);

                methods_meta.push(XrossMethod {
                    name: rust_fn_name.to_string(),
                    symbol: ffi_data.symbol_name.clone(),
                    method_type: ffi_data.method_type,
                    handle_mode,
                    safety: extract_safety_attr(&method.attrs, ThreadSafety::Lock),
                    is_constructor: false,
                    is_default: false,
                    is_raw: true,
                    is_async,
                    args: ffi_data.args_meta.clone(),
                    ret: ret_ty.clone(),
                    docs: extract_docs(&method.attrs),
                });

                // Generate raw FFI wrapper
                let export_ident = &ffi_data.export_ident;
                let c_args = &ffi_data.c_args;
                let receiver_conv =
                    if ffi_data.method_type != xross_metadata::XrossMethodType::Static {
                        let call_arg = &ffi_data.call_args[0];
                        quote!(let _self = #call_arg;)
                    } else {
                        quote!()
                    };

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
                    // If multiple FFI args map to one Rust arg (like String), it's still one _rust_args
                    // But if there are multiple FFI args and no import closure, we assume they map 1:1
                    if ffi_arg_names.len() > 1 {
                        quote! { let _rust_args = (#(#ffi_arg_names),*); }
                    } else if ffi_arg_names.len() == 1 {
                        let arg = &ffi_arg_names[0];
                        quote! { let _rust_args = #arg; }
                    } else {
                        quote! { let _rust_args = (); }
                    }
                };

                let rust_args_count = method.sig.inputs.len()
                    - if ffi_data.method_type == xross_metadata::XrossMethodType::Static {
                        0
                    } else {
                        1
                    };
                let rust_call_args = if rust_args_count > 1 {
                    let indices = (0..rust_args_count).map(syn::Index::from);
                    quote!(#(_rust_args.#indices),*)
                } else if rust_args_count == 1 {
                    quote!(_rust_args)
                } else {
                    quote!()
                };

                let rust_call = if ffi_data.method_type == xross_metadata::XrossMethodType::Static {
                    quote!(#type_name_ident::#rust_fn_name(#rust_call_args))
                } else {
                    quote!(_self.#rust_fn_name(#rust_call_args))
                };

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
                    let error_arm = crate::codegen::ffi::gen_panic_error_arm("raw method");
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
                            #receiver_conv
                            let res = { #wrapper_body };
                            unsafe { std::ptr::write(out, res) };
                        }
                    });
                } else {
                    extra_functions.push(quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_ty {
                            #receiver_conv
                            #wrapper_body
                        }
                    });
                }
                continue;
            }

            process_method_args(&method.sig.inputs, &package_name, type_name_ident, &mut ffi_data);

            let ret_ty = if is_new {
                xross_metadata::XrossType::Object {
                    signature: build_signature(&package_name, &type_name_ident.to_string()),
                    ownership: Ownership::Owned,
                }
            } else {
                resolve_return_type(
                    &method.sig.output,
                    &method.attrs,
                    &package_name,
                    type_name_ident,
                )
            };

            methods_meta.push(XrossMethod {
                name: rust_fn_name.to_string(),
                symbol: ffi_data.symbol_name.clone(),
                method_type: ffi_data.method_type,
                handle_mode,
                safety: extract_safety_attr(&method.attrs, ThreadSafety::Lock),
                is_constructor: is_new,
                is_default,
                is_raw: false,
                is_async,
                args: ffi_data.args_meta.clone(),
                ret: ret_ty.clone(),
                docs: extract_docs(&method.attrs),
            });

            let call_args = &ffi_data.call_args;
            let inner_call = quote! { #type_name_ident::#rust_fn_name(#(#call_args),*) };
            write_ffi_function(
                &ffi_data,
                &ret_ty,
                &method.sig.output,
                inner_call,
                handle_mode,
                &mut extra_functions,
            );
        }
    }

    match &mut definition {
        XrossDefinition::Struct(s) => s.methods.extend(methods_meta),
        XrossDefinition::Enum(e) => e.methods.extend(methods_meta),
        XrossDefinition::Opaque(o) => o.methods.extend(methods_meta),
        XrossDefinition::Function(_f) => {
            if !methods_meta.is_empty() {
                panic!("Cannot add methods to a standalone function definition.");
            }
        }
    }

    save_definition(&definition);
    quote! { #(#extra_functions)* #input_impl }
}
