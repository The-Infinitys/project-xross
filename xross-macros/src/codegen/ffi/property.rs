use proc_macro2::TokenStream;
use quote::{format_ident, quote};

/// Generates property accessors for types that need wrapping/unwrapping (Option, Result).
fn gen_complex_property_accessors(
    type_name: &syn::Ident,
    field_ident: &syn::Ident,
    field_ty: &syn::Type,
    xross_ty: &xross_metadata::XrossType,
    symbol_base: &str,
    suffix: &str,
    toks: &mut Vec<TokenStream>,
) {
    let field_name = field_ident.to_string();
    let get_fn = format_ident!("{}_property_{}_{}_get", symbol_base, field_name, suffix);
    let set_fn = format_ident!("{}_property_{}_{}_set", symbol_base, field_name, suffix);

    let (ret_ffi_ty, ret_wrap) = super::conversion::gen_ret_wrapping(
        xross_ty,
        &syn::ReturnType::Default,
        quote! { obj.#field_ident.clone() },
    );
    let arg_id = format_ident!("val");
    let (arg_ffi_ty, arg_conv, arg_call) =
        super::conversion::gen_arg_conversion(field_ty, &arg_id, xross_ty);

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> #ret_ffi_ty {
            if ptr.is_null() { panic!("NULL pointer in property get"); }
            let obj = &*ptr;
            #ret_wrap
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, #arg_ffi_ty) {
            if ptr.is_null() { panic!("NULL pointer in property set"); }
            let obj = &mut *ptr;
            #arg_conv
            obj.#field_ident = #arg_call;
        }
    });
}

/// Generates property accessors (getter/setter) for a field.
pub fn generate_property_accessors(
    type_name: &syn::Ident,
    field_ident: &syn::Ident,
    field_ty: &syn::Type,
    xross_ty: &xross_metadata::XrossType,
    symbol_base: &str,
    toks: &mut Vec<TokenStream>,
) {
    let field_name = field_ident.to_string();
    match xross_ty {
        xross_metadata::XrossType::String => {
            let get_fn = format_ident!("{}_property_{}_str_get", symbol_base, field_name);
            let set_fn = format_ident!("{}_property_{}_str_set", symbol_base, field_name);
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> *mut std::ffi::c_char {
                    if ptr.is_null() { panic!("NULL pointer in property get"); }
                    let obj = &*ptr;
                    std::ffi::CString::new(obj.#field_ident.as_str()).unwrap().into_raw()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, val: *const std::ffi::c_char) {
                    if ptr.is_null() || val.is_null() { return; }
                    let obj = &mut *ptr;
                    obj.#field_ident = std::ffi::CStr::from_ptr(val).to_string_lossy().into_owned();
                }
            });
        }
        xross_metadata::XrossType::Option(_inner) => {
            gen_complex_property_accessors(
                type_name,
                field_ident,
                field_ty,
                xross_ty,
                symbol_base,
                "opt",
                toks,
            );
        }
        xross_metadata::XrossType::Result { .. } => {
            gen_complex_property_accessors(
                type_name,
                field_ident,
                field_ty,
                xross_ty,
                symbol_base,
                "res",
                toks,
            );
        }
        _ => {
            let get_fn = format_ident!("{}_property_{}_get", symbol_base, field_name);
            let set_fn = format_ident!("{}_property_{}_set", symbol_base, field_name);
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> #field_ty {
                    if ptr.is_null() { panic!("NULL pointer in property get"); }
                    (*ptr).#field_ident.clone()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, val: #field_ty) {
                    if ptr.is_null() { panic!("NULL pointer in property set"); }
                    (*ptr).#field_ident = val;
                }
            });
        }
    }
}
