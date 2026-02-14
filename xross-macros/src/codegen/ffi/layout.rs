use proc_macro2::TokenStream;
use quote::quote;

/// Generates the layout specification for a single field.
pub fn gen_field_layout_spec(
    type_ident: &syn::Ident,
    field_access: TokenStream,
    field_name: &str,
    field_ty: &syn::Type,
) -> TokenStream {
    quote! {
        {
            let offset = std::mem::offset_of!(#type_ident, #field_access) as u64;
            let size = std::mem::size_of::<#field_ty>() as u64;
            format!("{}:{}:{}", #field_name, offset, size)
        }
    }
}

/// Generates the layout metadata logic for a struct.
pub fn generate_struct_layout(s: &syn::ItemStruct) -> TokenStream {
    let name = &s.ident;
    let mut field_parts = Vec::new();
    if let syn::Fields::Named(fields) = &s.fields {
        for field in &fields.named {
            let f_name = field.ident.as_ref().unwrap();
            let f_ty = &field.ty;
            field_parts.push(gen_field_layout_spec(
                name,
                quote! { #f_name },
                &f_name.to_string(),
                f_ty,
            ));
        }
    }
    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        #(parts.push(#field_parts);)*
        parts.join(";")
    }
}

/// Generates the layout metadata logic for an enum.
pub fn generate_enum_layout(e: &syn::ItemEnum) -> TokenStream {
    let name = &e.ident;
    let mut variant_specs = Vec::new();
    for v in &e.variants {
        let v_name = &v.ident;
        if v.fields.is_empty() {
            variant_specs.push(quote! { stringify!(#v_name).to_string() });
        } else {
            let mut fields_info = Vec::new();
            for (i, field) in v.fields.iter().enumerate() {
                let f_ty = &field.ty;
                let f_display_name = field
                    .ident
                    .as_ref()
                    .map(|id| id.to_string())
                    .unwrap_or_else(|| crate::utils::ordinal_name(i));
                let f_access = if let Some(ident) = &field.ident {
                    quote! { #v_name . #ident }
                } else {
                    let index = syn::Index::from(i);
                    quote! { #v_name . #index }
                };
                fields_info.push(gen_field_layout_spec(name, f_access, &f_display_name, f_ty));
            }
            variant_specs.push(quote! { format!("{}{{{}}}", stringify!(#v_name), vec![#(#fields_info),*].join(";")) });
        }
    }
    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        let variants: Vec<String> = vec![#(#variant_specs),*];
        parts.push(variants.join(";"));
        parts.join(";")
    }
}
