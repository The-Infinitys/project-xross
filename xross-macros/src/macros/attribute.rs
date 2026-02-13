use crate::codegen::ffi::{gen_ret_wrapping, process_method_args, resolve_return_type};
use crate::metadata::{load_definition, save_definition};
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{ImplItem, ItemImpl, Type};
use xross_metadata::{Ownership, ThreadSafety, XrossDefinition, XrossMethod, XrossMethodType};

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

            process_method_args(
                &method.sig.inputs,
                &package_name,
                type_name_ident,
                &mut c_args,
                &mut conversion_logic,
                &mut call_args,
                &mut args_meta,
                &mut method_type,
            );

            let ret_ty = if is_new {
                let sig = if package_name.is_empty() {
                    type_name_ident.to_string()
                } else {
                    format!("{}.{}", package_name, type_name_ident)
                };
                xross_metadata::XrossType::Object { signature: sig, ownership: Ownership::Owned }
            } else {
                resolve_return_type(&method.sig.output, &method.attrs, &package_name, type_name_ident)
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
