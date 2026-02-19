use crate::global::{CHUNK_SIZE, ChunkHeader, XrossGlobalAllocator};
use crate::slab::{
    CHUNK_HEADER_SIZE, LAYOUT_LEN, LocalSlab, SLAB_CLASS_SHIFT, SLAB_OFFSETS, SLAB_TOTAL_CAPACITY,
};
use rlsf::Tlsf;
use std::mem::MaybeUninit;
use std::ptr::{self, NonNull};
use std::sync::atomic::Ordering;

const TLSF_CAPACITY: usize = CHUNK_SIZE - SLAB_TOTAL_CAPACITY;

thread_local! {
    /// このスレッドのユニークなトークン（アドレス）
    pub static THREAD_TOKEN: u8 = const { 0 };
}

#[inline(always)]
pub fn current_thread_token() -> usize {
    THREAD_TOKEN.with(|t| t as *const u8 as usize)
}

pub struct XrossLocalAllocator {
    pub slabs: [LocalSlab; LAYOUT_LEN],
    pub tlsf: Tlsf<'static, usize, usize, 12, 16>,
    pub chunk_ptr: *mut u8,
    pub alloc_count: u32,
    pub global: &'static XrossGlobalAllocator,
}

impl XrossLocalAllocator {
    /// # Safety
    ///
    /// `chunk` must be a valid, 2MB-aligned pointer to a memory region of at least `CHUNK_SIZE`.
    /// `global` must outlive the created allocator.
    #[inline(always)]
    pub unsafe fn new(chunk: *mut u8, global: &'static XrossGlobalAllocator) -> Self {
        unsafe {
            // 所有権をセット
            let header = chunk as *mut ChunkHeader;
            (*header).owner_id.store(current_thread_token(), Ordering::Release);
            // ... (rest of the implementation)

            // slabs 配列を初期化
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

    /// オフセットからどのスラブクラスに属するかを判定 (O(1))
    #[inline(always)]
    pub fn get_slab_idx_from_offset(offset: usize) -> usize {
        // 全スラブが32KB固定なので、引き算とシフトで一発で計算可能
        (offset - CHUNK_HEADER_SIZE) >> SLAB_CLASS_SHIFT
    }

    /// # Safety
    ///
    /// This function must be called only by the owner thread of the chunk.
    #[inline(never)]
    pub unsafe fn process_remote_frees(&mut self) {
        unsafe {
            let header = self.chunk_ptr as *mut ChunkHeader;
            // メッセージパッシング: リモートからの解放リストをアトミックに取得
            let mut node = (*header).remote_free_list.swap(ptr::null_mut(), Ordering::Acquire);

            while !node.is_null() {
                let next = (*node).next;
                let ptr = node as *mut u8;
                let offset = ptr as usize - self.chunk_ptr as usize;

                if offset < SLAB_TOTAL_CAPACITY {
                    let slab_idx = Self::get_slab_idx_from_offset(offset);
                    self.slabs[slab_idx].dealloc(ptr);
                } else {
                    self.tlsf.deallocate(NonNull::new_unchecked(ptr), 16);
                }
                node = next;
            }
        }
    }
}

impl Drop for XrossLocalAllocator {
    fn drop(&mut self) {
        unsafe {
            self.process_remote_frees();
            // 所有権をクリアしてから返却
            let header = self.chunk_ptr as *mut ChunkHeader;
            (*header).owner_id.store(0, Ordering::Release);
            self.global.push_free_chunk(self.chunk_ptr);
        }
    }
}
