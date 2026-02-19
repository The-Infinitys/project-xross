use crate::global::{CHUNK_SIZE, XrossGlobalAllocator};
use crate::slab::{LAYOUT_LEN, LocalSlab, SLAB_OFFSETS, SLAB_TOTAL_CAPACITY};
use rlsf::Tlsf;
use std::mem::MaybeUninit;
use std::ptr::{self, NonNull};
use std::sync::atomic::Ordering;

const TLSF_CAPACITY: usize = CHUNK_SIZE - SLAB_TOTAL_CAPACITY;

pub struct XrossLocalAllocator {
    // ハードコードの [LocalSlab; 9] から LAYOUT_LEN に変更
    pub slabs: [LocalSlab; LAYOUT_LEN],
    pub tlsf: Tlsf<'static, usize, usize, 12, 16>,
    pub chunk_ptr: *mut u8,
    pub alloc_count: u32,
    pub global: &'static XrossGlobalAllocator,
}

impl XrossLocalAllocator {
    #[inline(always)]
    pub unsafe fn new(chunk: *mut u8, global: &'static XrossGlobalAllocator) -> Self {
        unsafe {
            // slabs 配列をループまたは unsafe な初期化で生成
            let mut slabs: [MaybeUninit<LocalSlab>; LAYOUT_LEN] =
                MaybeUninit::uninit().assume_init();

            for i in 0..LAYOUT_LEN {
                slabs[i].write(LocalSlab::new(chunk.add(SLAB_OFFSETS[i])));
            }

            // MaybeUninit から実体へ変換
            let slabs = std::mem::transmute_copy::<_, [LocalSlab; LAYOUT_LEN]>(&slabs);

            let mut tlsf = Tlsf::new();
            let tlsf_slice =
                ptr::slice_from_raw_parts_mut(chunk.add(SLAB_TOTAL_CAPACITY), TLSF_CAPACITY)
                    as *mut [MaybeUninit<u8>];

            tlsf.insert_free_block(&mut *tlsf_slice);

            Self { slabs, tlsf, chunk_ptr: chunk, alloc_count: 0, global }
        }
    }

    /// オフセットからどのスラブクラスに属するかを判定
    /// SLAB_OFFSETS を走査して適切なインデックスを返します
    #[inline(always)]
    pub fn get_slab_idx_from_offset(offset: usize) -> usize {
        // 二分探索、またはサイズが小さければ線形走査
        for i in 0..LAYOUT_LEN {
            if offset < SLAB_OFFSETS[i + 1] {
                return i;
            }
        }
        0 // unreachable if offset < SLAB_TOTAL_CAPACITY
    }

    #[inline(never)]
    pub unsafe fn process_remote_frees(&mut self) {
        unsafe {
            let chunk_idx = (self.chunk_ptr as usize - self.global.base_ptr as usize) / CHUNK_SIZE;
            let mut node = self.global.remote_free_queues[chunk_idx]
                .head
                .swap(ptr::null_mut(), Ordering::Acquire);

            while !node.is_null() {
                let next = (*(node as *mut crate::global::FreeNode)).next;
                let ptr = node as *mut u8;
                let offset = ptr as usize - self.chunk_ptr as usize;

                if offset < SLAB_TOTAL_CAPACITY {
                    let slab_idx = Self::get_slab_idx_from_offset(offset);
                    self.slabs[slab_idx].dealloc(ptr);
                } else {
                    // TLSFのdeallocateにはサイズ情報が必要な場合があります。
                    // 簡易的に最小アライメント(16)を指定していますが、実装に応じて調整してください。
                    self.tlsf.deallocate(NonNull::new_unchecked(ptr), 16);
                }
                node = next as *mut crate::global::FreeNode;
            }
        }
    }
}

impl Drop for XrossLocalAllocator {
    fn drop(&mut self) {
        unsafe {
            self.process_remote_frees();
            self.global.push_free_chunk(self.chunk_ptr);
        }
    }
}
