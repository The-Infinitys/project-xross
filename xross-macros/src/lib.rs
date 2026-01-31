use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{DeriveInput, FnArg, ImplItem, ItemImpl, parse_macro_input};

/// 構造体の基本メタデータ生成と共通処理のDerive
#[proc_macro_derive(JvmClass)]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    let struct_name = &input.ident;

    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown".to_string())
        .replace("-", "_");

    // 基本的なDropとCloneのシンボルをエクスポート
    let fn_drop = format_ident!("{}_{}_drop", crate_name, struct_name);
    let fn_clone = format_ident!("{}_{}_clone", crate_name, struct_name);

    let expanded = quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #fn_drop(ptr: *mut #struct_name) {
            if !ptr.is_null() {
                let _ = Box::from_raw(ptr);
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #fn_clone(ptr: *const #struct_name) -> *mut #struct_name {
            if ptr.is_null() { return std::ptr::null_mut(); }
            Box::into_raw(Box::new((*ptr).clone()))
        }

        impl #struct_name {
            pub const JVM_CRATE_NAME: &'static str = #crate_name;
            pub const JVM_STRUCT_NAME: &'static str = stringify!(#struct_name);
        }
    };

    TokenStream::from(expanded)
}

#[proc_macro_attribute]
pub fn jvm_export_impl(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input_impl = parse_macro_input!(item as ItemImpl);

    // 1. クレート名の取得 (ベースとして必ず使用)
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown".to_string())
        .replace("-", "_");

    // 2. 属性引数からパッケージ名を取得し、正規化して連結
    // #[jvm_export_impl(test.test2)] -> xross_macros_test_test2
    let prefix = if !attr.is_empty() {
        // TokenStreamを文字列として取得し、空白やドットを整理
        let attr_str = attr
            .to_string()
            .replace(" ", "")
            .replace(".", "_")
            .replace("/", "_");
        format!("{}_{}", crate_name, attr_str)
    } else {
        crate_name
    };

    let struct_name = if let syn::Type::Path(tp) = &*input_impl.self_ty {
        &tp.path
            .segments
            .last()
            .expect("Direct struct name required")
            .ident
    } else {
        panic!("jvm_export_impl must be used on a direct struct impl block");
    };

    let mut extra_functions = Vec::new();

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

            if is_new || is_method {
                let rust_fn_name = &method.sig.ident;

                // 3. 命名: クレート名_パッケージ名_構造体名_関数名
                let export_ident = format_ident!("{}_{}_{}", prefix, struct_name, rust_fn_name);

                let mut c_args = method.sig.inputs.clone();
                let mut call_args = Vec::new();
                let self_ptr_ident = format_ident!("self_ptr");

                for arg in c_args.iter_mut() {
                    match arg {
                        FnArg::Receiver(receiver) => {
                            let mutability = &receiver.mutability;
                            call_args.push(quote! { & #mutability *#self_ptr_ident });
                            let ptr_modifier = if mutability.is_some() {
                                quote! { mut }
                            } else {
                                quote! { const }
                            };
                            *arg = syn::parse_quote!(#self_ptr_ident: *#ptr_modifier #struct_name);
                        }
                        FnArg::Typed(pat_type) => {
                            if let syn::Pat::Ident(id) = &*pat_type.pat {
                                let name = id.ident.clone();
                                call_args.push(quote! { #name });
                            } else {
                                panic!("Unsupported argument pattern");
                            }
                        }
                    }
                }

                let wrapper = if is_new {
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#c_args) -> *mut #struct_name {
                            Box::into_raw(Box::new(#struct_name::#rust_fn_name(#(#call_args),*)))
                        }
                    }
                } else {
                    let ret = &method.sig.output;
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#c_args) #ret {
                            #struct_name::#rust_fn_name(#(#call_args),*)
                        }
                    }
                };
                extra_functions.push(wrapper);
            }
        }
    }

    let expanded = quote! {
        #input_impl
        #(#extra_functions)*
    };
    TokenStream::from(expanded)
}

// マーカーとしての属性（中身は空で良い）
#[proc_macro_attribute]
pub fn jvm_new(_attr: TokenStream, item: TokenStream) -> TokenStream {
    item
}

#[proc_macro_attribute]
pub fn jvm_method(_attr: TokenStream, item: TokenStream) -> TokenStream {
    item
}
