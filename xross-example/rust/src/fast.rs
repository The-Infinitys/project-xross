use xross_core::{XrossClass, xross_methods};

#[derive(XrossClass)]
#[xross_package("fast")]
#[repr(C)]
pub struct FastStruct {
    #[xross_field(safety = Direct, unsafe)]
    pub data: i32,
    #[xross_field(safety = Direct, unsafe)]
    pub name: String,
}

#[xross_methods]
impl FastStruct {
    #[xross_new]
    pub fn new(data: i32, name: String) -> Self {
        Self { data, name }
    }

    #[xross_method(critical(heap_access))]
    pub fn count_chars(&self, s: String) -> i32 {
        s.chars().count() as i32
    }
}
