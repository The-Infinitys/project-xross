use xross_core::{XrossClass, xross_function, xross_methods};

#[derive(XrossClass, Clone, Copy, Default)]
#[repr(C)]
pub struct ArrayTest {
    #[xross_field]
    pub data: [f64; 4],
}

#[xross_methods]
impl ArrayTest {
    #[xross_new]
    pub fn new(val: f64) -> Self {
        Self { data: [val; 4] }
    }

    #[xross_method]
    pub fn sum(&self) -> f64 {
        self.data.iter().sum()
    }
}

#[xross_function(package = "arrays")]
pub fn get_fixed_array() -> [i32; 8] {
    [1, 2, 3, 4, 5, 6, 7, 8]
}

#[xross_function(package = "arrays")]
pub fn sum_fixed_array(arr: [i32; 8]) -> i32 {
    arr.iter().sum()
}
