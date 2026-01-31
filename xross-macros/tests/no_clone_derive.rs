use xross_macros::JvmClass;
use xross_core::JvmClassTrait;

#[repr(C)]
#[derive(JvmClass, Default)]
#[jvm_class(crate = "xross_core")]
pub struct MyStruct {
    pub value: u32,
}