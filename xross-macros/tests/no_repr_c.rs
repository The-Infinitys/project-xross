use xross_macros::JvmClass;
use xross_core::JvmClassTrait;

#[derive(JvmClass, Clone, Default)]
#[jvm_class(crate = "xross_core")]
pub struct MyStruct {
    pub value: u32,
}