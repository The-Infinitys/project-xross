use std::ptr;

/// チャンクの先頭に配置するヘッダーのサイズ (バイト)
pub const CHUNK_HEADER_SIZE: usize = 256;

/// 各スラブクラスが占有するメモリサイズ (32KB)
pub const SLAB_CLASS_SIZE: usize = 32768;
/// 32KB を特定するためのビットシフト量
pub const SLAB_CLASS_SHIFT: usize = 15;

pub struct SlabConfig {
    pub size: usize,
    pub len: usize,
}

/// ここだけを編集すれば、すべてが自動計算される
pub const SLAB_LAYOUTS: &[SlabConfig] = &[
    SlabConfig { size: 8, len: 4096 },
    SlabConfig { size: 16, len: 2048 },
    SlabConfig { size: 32, len: 1024 },
    SlabConfig { size: 64, len: 512 },
    SlabConfig { size: 128, len: 256 },
    SlabConfig { size: 256, len: 128 },
    SlabConfig { size: 512, len: 64 },
    SlabConfig { size: 1024, len: 32 },
    SlabConfig { size: 2048, len: 16 },
];

pub const LAYOUT_LEN: usize = SLAB_LAYOUTS.len();

pub struct SlabLayout;
impl SlabLayout {
    /// 各スラブクラスの開始オフセット
    pub const OFFSETS: [usize; LAYOUT_LEN + 1] = {
        let mut offsets = [0; LAYOUT_LEN + 1];
        offsets[0] = CHUNK_HEADER_SIZE; // ヘッダー分を確保
        let mut i = 0;
        while i < LAYOUT_LEN {
            offsets[i + 1] = offsets[i] + (SLAB_LAYOUTS[i].size * SLAB_LAYOUTS[i].len);
            i += 1;
        }
        offsets
    };

    /// 個別のサイズ配列が必要な場合（get_slab_idx等で使用）
    pub const SIZES: [usize; LAYOUT_LEN] = {
        let mut sizes = [0; LAYOUT_LEN];
        let mut i = 0;
        while i < LAYOUT_LEN {
            sizes[i] = SLAB_LAYOUTS[i].size;
            i += 1;
        }
        sizes
    };

    pub const TOTAL_CAPACITY: usize = Self::OFFSETS[LAYOUT_LEN];
    pub const CHUNK_THRESHOLD: usize = SLAB_LAYOUTS[LAYOUT_LEN - 1].len;
}

// 外部用エイリアス
pub const SLAB_SIZES: [usize; LAYOUT_LEN] = SlabLayout::SIZES;
pub const SLAB_OFFSETS: [usize; LAYOUT_LEN + 1] = SlabLayout::OFFSETS;
pub const SLAB_TOTAL_CAPACITY: usize = SlabLayout::TOTAL_CAPACITY;

pub struct FreeNode {
    pub next: *mut FreeNode,
}

pub struct LocalSlab {
    pub base: *mut u8,
    pub bump_idx: u32,
    pub free_list: *mut FreeNode,
}

impl LocalSlab {
    /// # Safety
    ///
    /// `base` must point to a valid memory region of at least the size required
    /// for this slab class.
    pub unsafe fn new(base: *mut u8) -> Self {
        Self { base, bump_idx: 0, free_list: ptr::null_mut() }
    }

    /// # Safety
    ///
    /// `idx` must be a valid slab class index for this instance.
    #[inline(always)]
    pub unsafe fn alloc(&mut self, idx: usize) -> *mut u8 {
        if !self.free_list.is_null() {
            let node = self.free_list;
            unsafe {
                self.free_list = (*node).next;
            }
            return node as *mut u8;
        }
        if (self.bump_idx as usize) < SLAB_LAYOUTS[idx].len {
            unsafe {
                let ptr = self.base.add(self.bump_idx as usize * SLAB_SIZES[idx]);
                self.bump_idx += 1;
                return ptr;
            }
        }
        ptr::null_mut()
    }

    /// # Safety
    ///
    /// `ptr` must be a valid pointer to a memory block previously allocated
    /// from this slab instance.
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
