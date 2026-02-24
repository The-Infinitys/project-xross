use xross_core::{XrossClass, xross_methods};

#[derive(XrossClass, Clone, Copy, Debug)]
#[xross_package("fast")]
#[repr(C)]
pub struct Point {
    #[xross_field]
    pub x: i32,
    #[xross_field]
    pub y: i32,
}

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

    #[xross_raw_method {
        sig = (a: i32, b: i32) -> i32;
        import = |a, b| { (a, b) };
        export = |res| { res };
    }]
    pub fn add_raw(&self, a: i32, b: i32) -> i32 {
        a + b
    }

    #[xross_raw_method {
        sig = (p: Point) -> Point;
        import = |p| { p };
        export = |res| { res };
    }]
    pub fn move_point_raw(&self, mut p: Point) -> Point {
        p.x += 10;
        p.y += 20;
        p
    }
}
