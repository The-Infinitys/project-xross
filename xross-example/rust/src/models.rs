use crate::counters::{SERVICE_COUNT, SERVICE2_COUNT, UNKNOWN_STRUCT_COUNT};
use std::sync::atomic::Ordering;
use xross_core::{XrossClass, xross_class, xross_methods};

#[derive(XrossClass)]
#[xross(clonable)]
#[repr(C)]
pub struct UnknownStruct {
    #[xross_field]
    pub i: i32,
    #[xross_field]
    pub f: f32,
    #[xross_field]
    pub s: String,
}

// Clone時にもカウントを増やす
impl Clone for UnknownStruct {
    fn clone(&self) -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i: self.i, f: self.f, s: self.s.clone() }
    }
}

impl Drop for UnknownStruct {
    fn drop(&mut self) {
        UNKNOWN_STRUCT_COUNT.fetch_sub(1, Ordering::SeqCst);
    }
}

impl Default for UnknownStruct {
    fn default() -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i: 32, f: 64.0, s: "Hello, World!".to_string() }
    }
}

#[xross_methods]
impl UnknownStruct {
    #[xross_default]
    #[allow(clippy::should_implement_trait)]
    pub fn default() -> Self {
        <Self as Default>::default()
    }

    #[xross_new]
    pub fn new(i: i32, s: String, f: f32) -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i, s, f }
    }

    #[xross_new]
    pub fn with_int(i: i32) -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i, s: "From Int".to_string(), f: 0.0 }
    }

    #[xross_method]
    pub fn display_analysis() -> String {
        let s1 = SERVICE_COUNT.load(Ordering::SeqCst);
        let s2 = SERVICE2_COUNT.load(Ordering::SeqCst);
        let u = UNKNOWN_STRUCT_COUNT.load(Ordering::SeqCst);
        format!(
            "--- Xross Native Analysis ---

             Active MyService: {}

             Active MyService2: {}

             Active UnknownStruct: {}

             Total Native Objects: {}

             -----------------------------",
            s1,
            s2,
            u,
            s1 + s2 + u
        )
    }
}

pub struct ComplexStruct {
    pub opt: Option<i32>,
    pub res: Result<i32, String>,
}
impl ComplexStruct {
    pub fn new(opt: Option<i32>, res: Result<i32, String>) -> Self {
        Self { opt, res }
    }
    pub fn complex() {
        println!("ComplexStruct:");
    }
}
xross_class! {
    package complex;
    class struct ComplexStruct;
    clonable false;
    field opt: Option<i32>;
    field res: Result<i32, String>;
    method ComplexStruct.new(opt: Option<i32>, res: Result<i32, String>) -> ComplexStruct;
    method complex()
}

#[derive(Clone)]
pub struct ExternalStruct {
    pub value: i32,
    pub name: String,
}

impl ExternalStruct {
    pub fn new(value: i32, name: String) -> Self {
        Self { value, name }
    }
    pub fn get_value(&self) -> i32 {
        self.value
    }
    pub fn set_value(&mut self, v: i32) {
        self.value = v;
    }
    pub fn greet(&self, prefix: String) -> String {
        format!("{} {}", prefix, self.name)
    }
}

xross_class! {
    package external;
    class struct ExternalStruct;
    is_clonable true;
    field value: i32;
    field name: String;
    method ExternalStruct.new(value: i32, name: String) -> ExternalStruct;
    method &self.get_value() -> i32;
    method &mut self.set_value(v: i32);
    method &self.greet(prefix: String) -> String;
}

#[derive(XrossClass)]
pub struct PrimitiveTest {
    #[xross_field]
    pub u8_val: u8,
    #[xross_field]
    pub u32_val: u32,
    #[xross_field]
    pub u64_val: u64,
}

#[xross_methods]
impl PrimitiveTest {
    #[xross_new]
    pub fn new(u8_val: u8, u32_val: u32, u64_val: u64) -> Self {
        Self { u8_val, u32_val, u64_val }
    }

    #[xross_method]
    pub fn add_u32(&mut self, val: u32) {
        self.u32_val += val;
    }
}
