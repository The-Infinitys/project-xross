pub use xross_macros::*;
pub use xross_metadata::*;

#[unsafe(no_mangle)]
#[doc(hidden)]
pub unsafe extern "C" fn xross_free_string(ptr: *mut std::ffi::c_char) {
    if !ptr.is_null() {
        unsafe { drop(std::ffi::CString::from_raw(ptr)) }
    }
}
