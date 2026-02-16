use xross_core::{XrossClass, xross_methods};

#[derive(XrossClass, Clone, Default)]
#[xross(clonable)]
#[repr(C)]
pub struct ComprehensiveNode {
    #[xross_field]
    pub id: i32,
    #[xross_field]
    pub data: String,
}

#[xross_methods]
impl ComprehensiveNode {
    #[xross_new]
    pub fn new(id: i32, data: String) -> Self {
        Self { id, data }
    }
}

#[derive(XrossClass)]
#[repr(C)]
pub struct AllTypesTest {
    // Primitives
    #[xross_field]
    pub b: bool,
    #[xross_field]
    pub i8: i8,
    #[xross_field]
    pub u8: u8,
    #[xross_field]
    pub i16: i16,
    #[xross_field]
    pub u16: u16,
    #[xross_field]
    pub i32: i32,
    #[xross_field]
    pub u32: u32,
    #[xross_field]
    pub i64: i64,
    #[xross_field]
    pub u64: u64,
    #[xross_field]
    pub f32: f32,
    #[xross_field]
    pub f64: f64,
    #[xross_field]
    pub isize: isize,
    #[xross_field]
    pub usize: usize,

    // Complex
    #[xross_field]
    pub s: String,
    #[xross_field]
    pub node: ComprehensiveNode,
    #[xross_field]
    pub opt_i: Option<i32>,
    #[xross_field]
    pub opt_s: Option<String>,
    #[xross_field]
    pub opt_node: Option<ComprehensiveNode>,
    #[xross_field]
    pub res_i: Result<i32, String>,
}

#[xross_methods]
impl AllTypesTest {
    #[xross_new]
    pub fn new() -> Self {
        Self {
            b: true,
            i8: -8,
            u8: 8,
            i16: -16,
            u16: 16,
            i32: -32,
            u32: 32,
            i64: -64,
            u64: 64,
            f32: 32.0,
            f64: 64.0,
            isize: -1,
            usize: 1,
            s: "Initial".to_string(),
            node: ComprehensiveNode { id: 1, data: "Node".to_string() },
            opt_i: Some(42),
            opt_s: Some("Opt".to_string()),
            opt_node: None,
            res_i: Ok(100),
        }
    }

    // --- Ownership / Reference Tests ---

    #[xross_method]
    pub fn take_owned_node(&mut self, node: ComprehensiveNode) {
        self.node = node;
    }

    #[xross_method]
    pub fn take_ref_node(&self, node: &ComprehensiveNode) -> i32 {
        node.id
    }

    #[xross_method]
    pub fn take_mut_ref_node(&self, node: &mut ComprehensiveNode, new_id: i32) {
        node.id = new_id;
    }

    #[xross_method]
    pub fn return_owned_node(&self) -> ComprehensiveNode {
        self.node.clone()
    }

    #[xross_method]
    pub fn return_ref_node(&self) -> &ComprehensiveNode {
        &self.node
    }

    #[xross_method]
    pub fn return_mut_ref_node(&mut self) -> &mut ComprehensiveNode {
        &mut self.node
    }

    // --- Primitive References (Passed as values in FFI usually, but testable via wrappers) ---

    #[xross_method]
    pub fn multiply_i32(&self, a: i32, b: i32) -> i64 {
        (a as i64) * (b as i64)
    }

    // --- Option / Result Tests ---

    #[xross_method]
    pub fn test_options(
        &mut self,
        i: Option<i32>,
        s: Option<String>,
        node: Option<ComprehensiveNode>,
    ) {
        self.opt_i = i;
        self.opt_s = s;
        self.opt_node = node;
    }

    #[xross_method]
    pub fn get_res_i(&self, should_ok: bool) -> Result<i32, String> {
        if should_ok { Ok(self.i32) } else { Err("Error occurred".to_string()) }
    }

    #[xross_method]
    pub fn consume_self(self) -> String {
        self.s
    }
}
