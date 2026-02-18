use std::ptr;

pub const SLAB_SIZES: [usize; 9] = [8, 16, 32, 64, 128, 256, 512, 1024, 2048];
pub const SLAB_LENS: [usize; 9] = [4096, 2048, 1024, 512, 256, 128, 64, 32, 16];
pub const SLAB_OFFSETS: [usize; 10] =
    [0, 32768, 65536, 98304, 131072, 163840, 196608, 229376, 262144, 294912];
pub const SLAB_TOTAL_CAPACITY: usize = SLAB_OFFSETS[9];

pub struct FreeNode {
    pub next: *mut FreeNode,
}

pub struct LocalSlab {
    pub base: *mut u8,
    pub bump_idx: u32,
    pub free_list: *mut FreeNode,
}

impl LocalSlab {
    pub unsafe fn new(base: *mut u8) -> Self {
        Self { base, bump_idx: 0, free_list: ptr::null_mut() }
    }

    #[inline(always)]
    pub unsafe fn alloc(&mut self, idx: usize) -> *mut u8 {
        if !self.free_list.is_null() {
            let node = self.free_list;
            unsafe {
                self.free_list = (*node).next;
            }
            return node as *mut u8;
        }
        if (self.bump_idx as usize) < SLAB_LENS[idx] {
            unsafe {
                let ptr = self.base.add(self.bump_idx as usize * SLAB_SIZES[idx]);
                self.bump_idx += 1;
                return ptr;
            }
        }
        ptr::null_mut()
    }

    #[inline(always)]
    pub unsafe fn dealloc(&mut self, ptr: *mut u8) {
        let node = ptr as *mut FreeNode;
        // O(1) LIFO に戻す (性能重視)
        unsafe {
            (*node).next = self.free_list;
        }
        self.free_list = node;
    }
}
