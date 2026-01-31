use xross_macros::{JvmClass, jvm_impl};
use xross_core::JvmClassTrait;

#[repr(C)]
#[derive(JvmClass, Clone, Default)]
#[jvm_class(crate = "xross_core")]
pub struct MyStruct {
    pub value: u32,
    pub name: String,
    pub inner: InnerStruct,
}

#[repr(C)]
#[derive(JvmClass, Clone, Default)]
#[jvm_class(crate = "xross_core")]
pub struct InnerStruct {
    pub id: u64,
}

#[jvm_impl(crate = "xross_core")]
impl MyStruct {
    pub fn do_something(&self) {
        println!("MyStruct::do_something called for value: {}", self.value);
    }

    pub fn set_value_static(instance: &mut MyStruct, new_val: u32) {
        instance.value = new_val;
    }
}