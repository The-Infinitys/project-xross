use crate::codegen::ffi::{gen_arg_conversion, gen_ret_wrapping};
use crate::metadata::{load_definition, save_definition};
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{FnArg, ImplItem, ItemImpl, Pat, ReturnType, Type};
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossField, XrossMethod, XrossMethodType,
};

pub fn impl_xross_class_attribute(_attr: TokenStream, mut input_impl: ItemImpl) -> TokenStream {
    let type_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("xross_methods must be used on a direct type implementation");
    };

    let mut definition = load_definition(type_name_ident)
        .expect("XrossClass definition not found. Apply #[derive(XrossClass)] or xross_class! first.");

    let (package_name, symbol_base) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone()),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone()),
        XrossDefinition::Opaque(o) => (o.package_name.clone(), o.symbol_prefix.clone()),
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_method = false;
            method.attrs.retain(|attr| {
                if attr.path().is_ident("xross_new") {
                    is_new = true;
                    false
                } else if attr.path().is_ident("xross_method") {
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
                        method_type = if receiver.reference.is_none() {
                            XrossMethodType::OwnedInstance
                        } else if receiver.mutability.is_some() {
                            XrossMethodType::MutInstance
                        } else {
                            XrossMethodType::ConstInstance
                        };

                        c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                        if receiver.reference.is_none() {
                            call_args.push(
                                quote! { *Box::from_raw(#arg_ident as *mut #type_name_ident) },
                            );
                        } else if receiver.mutability.is_some() {
                            call_args.push(quote!(&mut *(#arg_ident as *mut #type_name_ident)));
                        } else {
                            call_args.push(quote!(&*(#arg_ident as *const #type_name_ident)));
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
                        );

                        args_meta.push(XrossField {
                            name: arg_name.clone(),
                            ty: xross_ty.clone(),
                            safety: extract_safety_attr(&pat_type.attrs, ThreadSafety::Lock),
                            docs: vec![],
                        });

                        let (c_arg, conv, call_arg) =
                            gen_arg_conversion(&pat_type.ty, &arg_ident, &xross_ty);
                        c_args.push(c_arg);
                        conversion_logic.push(conv);
                        call_args.push(call_arg);
                    }
                }
            }

            let ret_ty = if is_new {
                let sig = if package_name.is_empty() {
                    type_name_ident.to_string()
                } else {
                    format!("{}.{}", package_name, type_name_ident)
                };
                xross_metadata::XrossType::Object { signature: sig, ownership: Ownership::Owned }
            } else {
                match &method.sig.output {
                    ReturnType::Default => xross_metadata::XrossType::Void,
                    ReturnType::Type(_, ty) => {
                        let mut xross_ty = resolve_type_with_attr(
                            ty,
                            &method.attrs,
                            &package_name,
                            Some(type_name_ident),
                        );

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

                        if let xross_metadata::XrossType::Object { ownership: o, .. } = &mut xross_ty
                        {
                            *o = ownership;
                        }
                        xross_ty
                    }
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
            let (c_ret_type, wrapper_body) =
                gen_ret_wrapping(&ret_ty, &method.sig.output, inner_call);

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
        XrossDefinition::Struct(s) => s.methods.extend(methods_meta),
        XrossDefinition::Enum(e) => e.methods.extend(methods_meta),
        XrossDefinition::Opaque(o) => o.methods.extend(methods_meta),
    }

    save_definition(&definition);
    quote! { #input_impl #(#extra_functions)* }
}
