use std::ptr;
use std::sync::atomic::{AtomicPtr, AtomicUsize, Ordering};

pub const CHUNK_SIZE: usize = 2 * 1024 * 1024;
pub const MAX_CHUNKS: usize = 256;

#[repr(C, align(64))]
pub struct ChunkHeader {
    /// 所有者スレッドの識別子 (TLS変数のアドレス)
    pub owner_id: AtomicUsize,
    /// 他のスレッドからの解放リクエスト（メッセージパッシング用）
    pub remote_free_list: AtomicPtr<FreeNode>,
    /// 次のチャン（スレッド内でのリスト用、オプション）
    pub next_in_thread: AtomicPtr<ChunkHeader>,
}

pub struct FreeNode {
    pub next: *mut FreeNode,
}

pub struct XrossGlobalAllocator {
    pub base_ptr: *mut u8,
    pub size: usize,
    pub end_ptr: usize,
    pub next: AtomicUsize,
    pub free_chunks: AtomicPtr<FreeNode>,
}

unsafe impl Send for XrossGlobalAllocator {}
unsafe impl Sync for XrossGlobalAllocator {}

impl XrossGlobalAllocator {
    pub fn new(ptr: *mut u8, size: usize) -> Self {
        Self {
            base_ptr: ptr,
            size,
            end_ptr: ptr as usize + size,
            next: AtomicUsize::new(0),
            free_chunks: AtomicPtr::new(ptr::null_mut()),
        }
    }

    /// # Safety
    ///
    /// The caller must ensure that the allocator has been properly initialized
    /// and has enough free memory or can request it.
    #[inline(always)]
    pub unsafe fn alloc_chunk(&self) -> *mut u8 {
        let reused = unsafe { self.pop_free_chunk() };
        if !reused.is_null() {
            return reused;
        }

        let offset = self.next.fetch_add(CHUNK_SIZE, Ordering::Relaxed);
        if offset + CHUNK_SIZE <= self.size {
            let chunk_ptr = unsafe { self.base_ptr.add(offset) };
            // チャンクヘッダーの初期化
            let header = chunk_ptr as *mut ChunkHeader;
            unsafe {
                (*header).owner_id.store(0, Ordering::Relaxed);
                (*header).remote_free_list.store(ptr::null_mut(), Ordering::Relaxed);
                (*header).next_in_thread.store(ptr::null_mut(), Ordering::Relaxed);
            }
            chunk_ptr
        } else {
            ptr::null_mut()
        }
    }

    /// # Safety
    ///
    /// `chunk_ptr` must be a valid pointer to a 2MB chunk previously allocated
    /// from this or an equivalent allocator.
    #[inline(always)]
    pub unsafe fn push_free_chunk(&self, chunk_ptr: *mut u8) {
        // ヘッダーをクリア
        let header = chunk_ptr as *mut ChunkHeader;
        unsafe {
            (*header).owner_id.store(0, Ordering::Relaxed);
            (*header).remote_free_list.store(ptr::null_mut(), Ordering::Relaxed);
        }

        let node_ptr = chunk_ptr as *mut FreeNode;
        let mut head = self.free_chunks.load(Ordering::Acquire);
        loop {
            unsafe {
                (*node_ptr).next = head;
            }
            match self.free_chunks.compare_exchange_weak(
                head,
                node_ptr,
                Ordering::Release,
                Ordering::Acquire,
            ) {
                Ok(_) => break,
                Err(new_head) => head = new_head,
            }
        }
    }

    unsafe fn pop_free_chunk(&self) -> *mut u8 {
        let mut head = self.free_chunks.load(Ordering::Acquire);
        loop {
            if head.is_null() {
                return ptr::null_mut();
            }
            let next = unsafe { (*head).next };
            match self.free_chunks.compare_exchange_weak(
                head,
                next,
                Ordering::Release,
                Ordering::Acquire,
            ) {
                Ok(_) => return head as *mut u8,
                Err(new_head) => head = new_head,
            }
        }
    }

    #[inline(always)]
    pub fn is_own(&self, ptr: *mut u8) -> bool {
        let addr = ptr as usize;
        addr >= self.base_ptr as usize && addr < self.end_ptr
    }
}
