use syn::parse::{Parse, ParseStream};
use syn::punctuated::Punctuated;
use syn::{ExprClosure, FnArg, ReturnType, Token};

syn::custom_keyword!(sig);
syn::custom_keyword!(import);
syn::custom_keyword!(export);
syn::custom_keyword!(critical);
syn::custom_keyword!(panicable);
syn::custom_keyword!(heap_access);

/// Data parsed from #[xross_raw_method{...}] or #[xross_raw_function{...}]
pub struct RawAttrInput {
    pub sig_inputs: Punctuated<FnArg, Token![,]>,
    pub sig_output: ReturnType,
    pub import_closure: Option<ExprClosure>,
    pub export_closure: Option<ExprClosure>,
    pub handle_mode: xross_metadata::HandleMode,
}

impl Parse for RawAttrInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let mut sig_inputs = Punctuated::new();
        let mut sig_output = ReturnType::Default;
        let mut import_closure = None;
        let mut export_closure = None;
        let mut handle_mode = xross_metadata::HandleMode::Normal;

        while !input.is_empty() {
            if input.peek(sig) {
                input.parse::<sig>()?;
                input.parse::<Token![=]>()?;
                let content;
                syn::parenthesized!(content in input);
                sig_inputs = content.parse_terminated(FnArg::parse, Token![,])?;
                if input.peek(Token![->]) {
                    sig_output = input.parse::<ReturnType>()?;
                }
                input.parse::<Token![;]>()?;
            } else if input.peek(import) {
                input.parse::<import>()?;
                input.parse::<Token![=]>()?;
                import_closure = Some(input.parse::<ExprClosure>()?);
                input.parse::<Token![;]>()?;
            } else if input.peek(export) {
                input.parse::<export>()?;
                input.parse::<Token![=]>()?;
                export_closure = Some(input.parse::<ExprClosure>()?);
                input.parse::<Token![;]>()?;
            } else if input.peek(critical) {
                input.parse::<critical>()?;
                let mut allow_heap_access = false;
                if input.peek(syn::token::Paren) {
                    let content;
                    syn::parenthesized!(content in input);
                    if content.peek(heap_access) {
                        content.parse::<heap_access>()?;
                        allow_heap_access = true;
                    }
                }
                handle_mode = xross_metadata::HandleMode::Critical { allow_heap_access };
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
            } else if input.peek(panicable) {
                input.parse::<panicable>()?;
                handle_mode = xross_metadata::HandleMode::Panicable;
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
            } else {
                return Err(input.error("Expected sig, import, export, critical, or panicable"));
            }
        }

        Ok(RawAttrInput { sig_inputs, sig_output, import_closure, export_closure, handle_mode })
    }
}
