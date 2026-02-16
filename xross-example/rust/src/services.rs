use crate::counters::{SERVICE_COUNT, SERVICE2_COUNT};
use crate::enums::{XrossSimpleEnum, XrossTestEnum};
use crate::models::UnknownStruct;
use std::cmp::{max, min};
use std::sync::atomic::Ordering;
use xross_core::{XrossClass, xross_methods};

#[derive(XrossClass)]
#[xross(clonable)]
#[repr(C)]
pub struct MyService {
    _boxes: Vec<i32>,
    #[xross_field]
    pub unknown_struct: Box<UnknownStruct>,
}

impl Clone for MyService {
    fn clone(&self) -> Self {
        SERVICE_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { _boxes: self._boxes.clone(), unknown_struct: self.unknown_struct.clone() }
    }
}

impl Drop for MyService {
    fn drop(&mut self) {
        SERVICE_COUNT.fetch_sub(1, Ordering::SeqCst);
    }
}

impl Default for MyService {
    fn default() -> Self {
        Self::new()
    }
}

#[xross_methods]
impl MyService {
    #[xross_new]
    pub fn new() -> Self {
        SERVICE_COUNT.fetch_add(1, Ordering::SeqCst);
        MyService { _boxes: vec![0; 1_000_000], unknown_struct: Box::new(UnknownStruct::default()) }
    }

    #[xross_method]
    pub async fn async_execute(&self, val: i32) -> i32 {
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        val * 2
    }

    #[xross_method]
    pub fn consume_self(self) -> i32 {
        self._boxes.len() as i32
    }

    #[xross_method]
    pub fn ret_enum(&self) -> XrossTestEnum {
        match rand::random_range(0..3) {
            1 => XrossTestEnum::B { i: rand::random() },
            2 => XrossTestEnum::C { j: Box::new(UnknownStruct::default()) },
            _ => XrossTestEnum::A,
        }
    }

    #[xross_method(critical)]
    pub fn add_trivial(&self, a: i32, b: i32) -> i32 {
        a + b
    }

    #[xross_method(critical(heap_access))]
    pub fn add_critical_heap(&self, a: i32, b: i32) -> i32 {
        a + b
    }

    #[xross_method(panicable)]
    pub fn cause_panic(&self, should_panic: u8) -> String {
        if should_panic != 0 {
            panic!("Intentional panic from Rust!");
        }
        "No panic today".to_string()
    }

    #[xross_method]
    pub fn execute(&mut self, i: usize) -> i32 {
        let a = self._boxes.len();
        let x = min(a, i);
        let y = max(a, i);
        rand::random_range(x..y + 1) as i32
    }

    #[xross_method]
    pub fn get_option_enum(&self, should_some: bool) -> Option<XrossSimpleEnum> {
        if should_some { Some(XrossSimpleEnum::V) } else { None }
    }

    #[xross_method]
    pub fn get_result_struct(&self, should_ok: bool) -> Result<MyService2, String> {
        if should_ok { Ok(MyService2::new(1)) } else { Err("Error".to_string()) }
    }
}

#[derive(XrossClass)]
#[xross_package("test.test2")]
#[xross(clonable)]
#[repr(C)]
pub struct MyService2 {
    #[xross_field(safety = Atomic)]
    pub val: i32,
}

impl Clone for MyService2 {
    fn clone(&self) -> Self {
        SERVICE2_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { val: self.val }
    }
}

impl Drop for MyService2 {
    fn drop(&mut self) {
        SERVICE2_COUNT.fetch_sub(1, Ordering::SeqCst);
    }
}

#[xross_methods]
impl MyService2 {
    #[xross_new]
    pub fn new(val: i32) -> Self {
        SERVICE2_COUNT.fetch_add(1, Ordering::SeqCst);
        MyService2 { val }
    }

    #[xross_method]
    pub fn create_clone(&self) -> Self {
        self.clone()
    }

    #[xross_method]
    pub fn get_self_ref(&self) -> &Self {
        self
    }

    #[xross_method]
    pub fn execute(&self) -> f64 {
        if self.val == 0 {
            return 0.0;
        }
        let low = min(-self.val, self.val);
        let high = max(-self.val, self.val);
        rand::random_range(low..high + 1) as f64
    }
}
