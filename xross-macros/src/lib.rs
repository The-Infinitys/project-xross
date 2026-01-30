extern crate proc_macro;

use proc_macro::TokenStream;
use quote::{quote, format_ident};
use syn::{
    parse_macro_input,
    DeriveInput,
    Attribute,
    Meta,
    NestedMeta,
    Data,
    Fields,
    Type,
    Ident,
    Visibility,
    PathArguments,
    PathSegment,
    ImplItem,
    ImplItemMethod,
    ItemImpl // ItemImplを追加
};
use serde::{Serialize, Deserialize}; // serdeを追加
use std::{fs, io, path::PathBuf}; // fs, io, PathBufを追加

/// Helper function to check if a struct has a specific `#[derive(...)]` attribute.
fn has_derive_attribute(attrs: &[Attribute], trait_name: &str) -> bool {
    attrs.iter().any(|attr| {
        attr.path().is_ident("derive") &&
        attr.parse_args::<syn::MetaList>().ok().map_or(false, |meta_list| {
            meta_list.nested.iter().any(|nested_meta| {
                if let syn::NestedMeta::Meta(syn::Meta::Path(path)) = nested_meta {
                    path.is_ident(trait_name)
                } else {
                    false
                }
            })
        })
    })
}

// プリミティブ型を識別するヘルパー
fn is_primitive_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            let ident_str = segment.ident.to_string();
            matches!(
                ident_str.as_str(),
                "u8" | "i8" | "u16" | "i16" | "u32" | "i32" | "u64" | "i64" | "f32" | "f64" | "bool"
            )
        } else {
            false
        }
    } else {
        false
    }
}

// String型を識別するヘルパー
fn is_string_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            segment.ident == "String" && segment.arguments.is_empty()
        } else {
            false
        }
    } else {
        false
    }
}

// JvmClassな構造体型を識別するヘルパー (JvmClassTraitを実装しているか)
fn is_jvm_class_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            let ident_str = segment.ident.to_string();
            !(is_primitive_type(ty) || is_string_type(ty) || ident_str == "Option" || ident_str == "Vec")
        } else {
            false
        }
    } else {
        false
    }
}

// FFI関数名のプレフィックスを生成する共通ロジック
fn generate_ffi_prefix(crate_name: &str, struct_name_str: &str) -> String {
    let module_path_token_stream = quote! { module_path!() };
    let module_path_str = module_path_token_stream.to_string();
    let module_path_ffi = module_path_str
        .replace("::", "_")
        .replace(" ", "")
        .replace("!", "")
        .replace("(", "")
        .replace(")", "");
    format!("{}_{}_{}", crate_name, module_path_ffi, struct_name_str)
}

// メタデータ構造体の定義
#[derive(Debug, Serialize, Deserialize)]
struct FieldMetadata {
    pub name: String,
    pub rust_type: String,
    pub ffi_getter_name: String,
    pub ffi_setter_name: String,
    pub ffi_type: String, // C-ABIでの型表現 (例: "int32_t", "*const char", "*mut SomeStruct")
}

#[derive(Debug, Serialize, Deserialize)]
struct MethodMetadata {
    pub name: String,
    pub ffi_name: String,
    pub args: Vec<String>, // 引数の型リスト (Rust Type)
    pub return_type: String, // 戻り値の型 (Rust Type)
    pub has_self: bool, // `&self`, `&mut self`, `self` のいずれかがあるか
    pub is_static: bool, // `self`を取らないassoc functionの場合
}

#[derive(Debug, Serialize, Deserialize)]
struct StructMetadata {
    pub name: String,
    pub ffi_prefix: String,
    pub new_fn_name: String,
    pub drop_fn_name: String,
    pub clone_fn_name: String,
    pub fields: Vec<FieldMetadata>,
    pub methods: Vec<MethodMetadata>,
}

#[proc_macro_derive(JvmClass, attributes(jvm_class))] // attributes(jvm_class)を追加
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let ast = parse_macro_input!(input as DeriveInput);
    let name = &ast.ident; // MyStruct
    let name_str = name.to_string(); // "MyStruct"

    let mut crate_name_opt: Option<String> = None;
    for attr in &ast.attrs {
        if attr.path().is_ident("jvm_class") {
            if let Ok(Meta::List(meta_list)) = syn::parse_macro_input::parse(attr.tokens.clone().into()) { // attr.parse_meta() から attr.tokens.clone().into() に変更
                for nested in meta_list.nested {
                    if let NestedMeta::Meta(Meta::NameValue(nv)) = nested {
                        if nv.path.is_ident("crate") {
                            if let syn::Lit::Str(lit_str) = nv.lit {
                                crate_name_opt = Some(lit_str.value());
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    let crate_name = match crate_name_opt {
        Some(c) => c,
        None => return TokenStream::from(quote! {
            compile_error!("Struct deriving JvmClass must have #[jvm_class(crate = \"your_crate_name\")] attribute.");
        }),
    };

    // repr(C) アトリビュートが存在するかチェック
    let has_repr_c = ast.attrs.iter().any(|attr| {
        attr.path().is_ident("repr") && attr.parse_args::<syn::Ident>().ok().map_or(false, |ident| ident == "C")
    });

    if !has_repr_c {
        return TokenStream::from(quote! {
            compile_error!("Struct deriving JvmClass must have #[repr(C)] attribute.");
        });
    }

    // Clone アトリビュートが存在するかチェック
    let has_clone = has_derive_attribute(&ast.attrs, "Clone");

    if !has_clone {
        return TokenStream::from(quote! {
            compile_error!("Struct deriving JvmClass must have #[derive(Clone)] attribute.");
        });
    }

    let ffi_prefix = generate_ffi_prefix(&crate_name, &name_str);

    let new_fn_name = format_ident!("{}_new", ffi_prefix);
    let drop_fn_name = format_ident!("{}_drop", ffi_prefix);
    let clone_fn_name = format_ident!("{}_clone", ffi_prefix);

    let mut getter_setter_fns = TokenStream::new();
    let mut field_metadata_list = Vec::new(); // フィールドメタデータ収集用

    if let Data::Struct(data_struct) = &ast.data {
        if let Fields::Named(fields) = &data_struct.fields {
            for field in &fields.named {
                // pubフィールドのみを対象とする
                if let Visibility::Public(_) = &field.vis {
                    let field_name = field.ident.as_ref().unwrap();
                    let field_name_str = field_name.to_string();
                    let field_type = &field.ty;
                    let field_type_str = quote! { #field_type }.to_string().replace(" ", "");

                    let getter_name = format_ident!("{}_get_{}", ffi_prefix, field_name_str);
                    let setter_name = format_ident!("{}_set_{}", ffi_prefix, field_name_str);
                    let getter_name_str = getter_name.to_string();
                    let setter_name_str = setter_name.to_string();

                    let mut current_ffi_type = String::new();

                    // 型による分岐
                    if is_primitive_type(field_type) {
                        let ffi_type = field_type; // プリミティブ型はそのまま
                        current_ffi_type = quote!{ #ffi_type }.to_string(); // Rustの型名をFFI型名として利用
                        getter_setter_fns.extend(TokenStream::from(quote! {
                            #[no_mangle]
                            pub extern "C" fn #getter_name(ptr: *const #name) -> #ffi_type {
                                assert!(!ptr.is_null(), "Attempted to get field '{}' from a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                unsafe {
                                    (*ptr).#field_name
                                }
                            }

                            #[no_mangle]
                            pub extern "C" fn #setter_name(ptr: *mut #name, value: #ffi_type) {
                                assert!(!ptr.is_null(), "Attempted to set field '{}' on a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                unsafe {
                                    (*ptr).#field_name = value;
                                }
                            }
                        }));
                    } else if is_string_type(field_type) {
                        current_ffi_type = "*const libc::c_char".to_string();
                        getter_setter_fns.extend(TokenStream::from(quote! {
                            #[no_mangle]
                            pub extern "C" fn #getter_name(ptr: *const #name) -> *const libc::c_char {
                                assert!(!ptr.is_null(), "Attempted to get field '{}' from a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                unsafe {
                                    let c_str = std::ffi::CString::new((*ptr).#field_name.as_bytes()).expect("CString::new failed");
                                    c_str.into_raw()
                                }
                            }

                            #[no_mangle]
                            pub extern "C" fn #setter_name(ptr: *mut #name, value: *const libc::c_char) {
                                assert!(!ptr.is_null(), "Attempted to set field '{}' on a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                assert!(!value.is_null(), "Attempted to set field '{}' with a null C string for {}", stringify!(#field_name), stringify!(#name));
                                unsafe {
                                    let c_str = std::ffi::CStr::from_ptr(value);
                                    (*ptr).#field_name = String::from_utf8_lossy(c_str.to_bytes()).to_string();
                                }
                            }
                        }));
                    } else if is_jvm_class_type(field_type) {
                        current_ffi_type = format!("*mut {}", field_type_str);
                        getter_setter_fns.extend(TokenStream::from(quote! {
                            #[no_mangle]
                            pub extern "C" fn #getter_name(ptr: *const #name) -> *mut #field_type {
                                assert!(!ptr.is_null(), "Attempted to get field '{}' from a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                unsafe {
                                    Box::into_raw(Box::new((*ptr).#field_name.clone()))
                                }
                            }

                            #[no_mangle]
                            pub extern "C" fn #setter_name(ptr: *mut #name, value: *mut #field_type) {
                                assert!(!ptr.is_null(), "Attempted to set field '{}' on a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                assert!(!value.is_null(), "Attempted to set field '{}' with a null pointer for {}", stringify!(#field_name), stringify!(#name));
                                unsafe {
                                    (*ptr).#field_name = *Box::from_raw(value);
                                }
                            }
                        }));
                    } else {
                        return TokenStream::from(quote! {
                            compile_error!(concat!("Field '", stringify!(#field_name), "' has an unsupported type for JvmClass FFI: ", stringify!(#field_type)));
                        });
                    }

                    // フィールドメタデータを収集
                    field_metadata_list.push(FieldMetadata {
                        name: field_name_str,
                        rust_type: field_type_str.clone(),
                        ffi_getter_name: getter_name_str,
                        ffi_setter_name: setter_name_str,
                        ffi_type: current_ffi_type,
                    });
                }
            }
        } else {
            return TokenStream::from(quote! {
                compile_error!("JvmClass can only be derived for structs with named fields.");
            });
        }
    } else {
        return TokenStream::from(quote! {
            compile_error!("JvmClass can only be derived for structs.");
        });
    }

    let struct_metadata = StructMetadata {
        name: name_str.clone(),
        ffi_prefix: ffi_prefix.clone(),
        new_fn_name: new_fn_name.to_string(),
        drop_fn_name: drop_fn_name.to_string(),
        clone_fn_name: clone_fn_name.to_string(),
        fields: field_metadata_list,
        methods: Vec::new(), // #[jvm_impl]で追加される
    };

    // メタデータをJSONとして一時ファイルに書き出す
    let out_dir = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR not set"));
    let metadata_file_name = format!("{}_struct_metadata.json", ffi_prefix);
    let metadata_path = out_dir.join(metadata_file_name);
    
    // シリアライズしてファイルに書き込み
    fs::write(
        &metadata_path,
        serde_json::to_string_pretty(&struct_metadata).expect("Failed to serialize struct metadata")
    ).expect("Failed to write struct metadata to file");


    let expanded = quote! {
        // JvmClassTraitを実装する
        impl xross_core::JvmClassTrait for #name {
            fn new() -> Self {
                <#name as Default>::default()
            }
        }

        // C-ABI互換のnew関数 (引数なし)
        #[no_mangle]
        pub extern "C" fn #new_fn_name() -> *mut #name {
            Box::into_raw(Box::new(<#name as xross_core::JvmClassTrait>::new()))
        }

        // C-ABI互換のdrop関数
        #[no_mangle]
        pub extern "C" fn #drop_fn_name(ptr: *mut #name) {
            if !ptr.is_null() {
                // ポインタからBoxを再構築してdropを呼び出す
                unsafe {
                    drop(Box::from_raw(ptr));
                }
            }
        }

        // C-ABI互換のclone関数
        #[no_mangle]
        pub extern "C" fn #clone_fn_name(ptr: *const #name) -> *mut #name {
            assert!(!ptr.is_null(), "Attempted to clone a null pointer for {}", stringify!(#name));
            unsafe {
                let instance = &*ptr; // 不変参照を取得
                Box::into_raw(Box::new(instance.clone())) // Cloneトレイトを呼び出し、新しいポインタを返す
            }
        }

        // フィールドのGetter/Setter関数
        #getter_setter_fns
    };

    expanded.into()
}

// #[jvm_impl] アトリビュートマクロ
#[proc_macro_attribute]
pub fn jvm_impl(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut crate_name_opt: Option<String> = None;
    // attr (例: #[jvm_impl(crate = "my_crate")]) をパース
    let input_attr_meta = parse_macro_input!(attr as Meta); // Metaをパースするように修正

    if let Meta::List(meta_list) = input_attr_meta {
        if meta_list.path.is_ident("jvm_impl") { // #[jvm_impl] 属性自体であるか確認
            for nested in meta_list.nested {
                if let NestedMeta::Meta(Meta::NameValue(nv)) = nested {
                    if nv.path.is_ident("crate") {
                        if let syn::Lit::Str(lit_str) = nv.lit {
                            crate_name_opt = Some(lit_str.value());
                        }
                    }
                }
            }
        }
    }
    
    let crate_name = match crate_name_opt {
        Some(c) => c,
        None => return TokenStream::from(quote! {
            compile_error!("#[jvm_impl] attribute must have #[jvm_impl(crate = \"your_crate_name\")] attribute.");
        }),
    };

    let mut ast = parse_macro_input!(item as ItemImpl);
    let self_ty = &ast.self_ty;
    let self_ty_str = quote!{ #self_ty }.to_string().replace(" ", "");

    let ffi_prefix = generate_ffi_prefix(&crate_name, &self_ty_str);

    let mut generated_fns = TokenStream::new();
    let mut method_metadata_list = Vec::new(); // メソッドメタデータ収集用

    for item in &mut ast.items {
        if let ImplItem::Method(method) = item {
            if let Visibility::Public(_) = &method.vis {
                let method_name = &method.sig.ident;
                let method_name_str = method_name.to_string();

                let ffi_method_name = format_ident!("{}_impl_{}", ffi_prefix, method_name_str);
                let ffi_method_name_str = ffi_method_name.to_string();

                // TODO: メソッドの引数と戻り値の型変換ロジック
                // TODO: selfの有無によるシグネチャ調整

                let has_self = method.sig.receiver().is_some();
                let is_static = !has_self;
                let args: Vec<String> = method.sig.inputs.iter().map(|arg| {
                    match arg {
                        syn::FnArg::Receiver(_) => "Self".to_string(), // Self型を表現
                        syn::FnArg::Typed(pat_type) => quote! { #pat_type }.to_string().replace(" ", ""),
                    }
                }).collect();
                let return_type_str = if let syn::ReturnType::Type(_, ty) = &method.sig.output {
                    quote! { #ty }.to_string().replace(" ", "")
                } else {
                    "()".to_string()
                };


                // メソッドメタデータを収集
                method_metadata_list.push(MethodMetadata {
                    name: method_name_str.clone(),
                    ffi_name: ffi_method_name_str,
                    args: args,
                    return_type: return_type_str,
                    has_self: has_self,
                    is_static: is_static,
                });

                // とりあえず、ダミーのFFIラッパーを生成
                generated_fns.extend(TokenStream::from(quote! {
                    #[no_mangle]
                    pub extern "C" fn #ffi_method_name() {
                        // TODO: 実際のメソッド呼び出しロジック
                        unimplemented!("FFI method '{}' not yet implemented for {}", stringify!(#method_name), stringify!(#self_ty));
                    }
                }));
            }
        }
    }

    // メタデータをJSONとして一時ファイルに書き出す
    let out_dir = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR not set"));
    let metadata_file_name = format!("{}_method_metadata.json", ffi_prefix);
    let metadata_path = out_dir.join(metadata_file_name);
    
    // シリアライズしてファイルに書き込み
    fs::write(
        &metadata_path,
        serde_json::to_string_pretty(&method_metadata_list).expect("Failed to serialize method metadata")
    ).expect("Failed to write method metadata to file");


    TokenStream::from(quote! {
        #ast // 元の impl ブロック
        #generated_fns // 生成された FFI 関数
    })
}
