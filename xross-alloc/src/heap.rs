use crate::global::{CHUNK_SIZE, MAX_CHUNKS};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::OnceLock;

/// JVMから提供された生ポインタをスレッドセーフに扱うためのラッパー
#[derive(Clone, Copy)]
pub struct RawMemory {
    pub ptr: *mut u8,
    pub size: usize,
    pub align: usize,
}
impl RawMemory {
    /// # Safety
    ///
    /// The caller must ensure that `size` and `align` are valid and that the
    /// memory can be safely allocated from the system.
    pub unsafe fn new(size: usize, align: usize) -> Self {
        let ptr = unsafe { System.alloc(Layout::from_size_align(size, align).unwrap()) };
        Self { ptr, size, align }
    }
    pub fn is_own(&self, ptr: *mut u8) -> bool {
        let start = self.ptr as usize;
        let end = start + self.size;
        let target = ptr as usize;
        // start <= target < end の範囲にあるかを確認
        target >= start && target < end
    }
}
unsafe impl Send for RawMemory {}
unsafe impl Sync for RawMemory {}

/// JVMから提供されるメモリアロケータの型定義
type XrossAllocUpcall = unsafe extern "C" fn(usize, usize) -> *mut u8;

/// JVMから提供されるアロケータへのアップコール
static ALLOCATOR_UPCALL: OnceLock<XrossAllocUpcall> = OnceLock::new();

/// JVMまたはシステムから提供される固定メモリ領域
static HEAP_SOURCE: OnceLock<RawMemory> = OnceLock::new();

/// JVMからメモリを要求します。
/// JVM側で Arena.global() などを使用して割り当てられたメモリを返します。
///
/// # Safety
///
/// The caller must ensure that the allocator is properly initialized
/// and that the size and align requests are valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xross_alloc(size: usize, align: usize) -> *mut u8 {
    if let Some(upcall) = ALLOCATOR_UPCALL.get() {
        unsafe { upcall(size, align) }
    } else {
        // フォールバック: システムアロケータを使用
        unsafe { std::alloc::alloc(Layout::from_size_align(size, align).unwrap()) }
    }
}

/// ヒープ領域を取得します。
pub fn heap() -> RawMemory {
    *HEAP_SOURCE.get_or_init(|| {
        let size = CHUNK_SIZE * MAX_CHUNKS;
        let align = CHUNK_SIZE;
        let ptr = unsafe { xross_alloc(size, align) };
        RawMemory { ptr, size, align }
    })
}

#[cfg(feature = "jvm")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xross_alloc_init(upcall: XrossAllocUpcall) {
    let _ = ALLOCATOR_UPCALL.set(upcall);
}
