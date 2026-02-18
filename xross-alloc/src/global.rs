use std::ptr;
use std::sync::atomic::{AtomicPtr, AtomicUsize, Ordering};

pub const CHUNK_SIZE: usize = 2 * 1024 * 1024;
pub const MAX_CHUNKS: usize = 256;

#[repr(align(64))]
pub struct RemoteFreeQueue {
    pub head: AtomicPtr<FreeNode>,
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
    pub remote_free_queues: [RemoteFreeQueue; MAX_CHUNKS],
}

unsafe impl Send for XrossGlobalAllocator {}
unsafe impl Sync for XrossGlobalAllocator {}

impl XrossGlobalAllocator {
    pub fn new(ptr: *mut u8, size: usize) -> Self {
        const EMPTY_PTR: RemoteFreeQueue =
            RemoteFreeQueue { head: AtomicPtr::new(ptr::null_mut()) };
        Self {
            base_ptr: ptr,
            size,
            end_ptr: ptr as usize + size,
            next: AtomicUsize::new(0),
            free_chunks: AtomicPtr::new(ptr::null_mut()),
            remote_free_queues: [EMPTY_PTR; MAX_CHUNKS],
        }
    }

    #[inline(always)]
    pub unsafe fn alloc_chunk(&self) -> *mut u8 {
        let reused = unsafe { self.pop_free_chunk() };
        if !reused.is_null() {
            return reused;
        }

        let offset = self.next.fetch_add(CHUNK_SIZE, Ordering::Relaxed);
        if offset + CHUNK_SIZE <= self.size {
            unsafe { self.base_ptr.add(offset) }
        } else {
            ptr::null_mut()
        }
    }

    #[inline(always)]
    pub unsafe fn push_free_chunk(&self, chunk_ptr: *mut u8) {
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

    #[inline(always)]
    pub unsafe fn push_remote_free(&self, ptr: *mut u8) {
        let offset = ptr as usize - (self.base_ptr as usize);
        let chunk_idx = offset / CHUNK_SIZE;
        if chunk_idx >= MAX_CHUNKS {
            return;
        }

        let node_ptr = ptr as *mut FreeNode;
        let queue = &self.remote_free_queues[chunk_idx].head;
        let mut head = queue.load(Ordering::Relaxed);
        loop {
            unsafe {
                (*node_ptr).next = head;
            }
            match queue.compare_exchange_weak(head, node_ptr, Ordering::Release, Ordering::Relaxed)
            {
                Ok(_) => break,
                Err(new_head) => head = new_head,
            }
        }
    }
}
