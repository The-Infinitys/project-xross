use crate::global::{CHUNK_SIZE, XrossGlobalAllocator};
use crate::slab::{LocalSlab, SLAB_OFFSETS, SLAB_TOTAL_CAPACITY};
use rlsf::Tlsf;
use std::mem::MaybeUninit;
use std::ptr::{self, NonNull};
use std::sync::atomic::Ordering;

const TLSF_CAPACITY: usize = CHUNK_SIZE - SLAB_TOTAL_CAPACITY;

pub struct XrossLocalAllocator {
    pub slabs: [LocalSlab; 9],
    pub tlsf: Tlsf<'static, usize, usize, 12, 16>,
    pub chunk_ptr: *mut u8,
    pub alloc_count: u32,
    pub global: &'static XrossGlobalAllocator,
}

impl XrossLocalAllocator {
    #[inline(always)]
    pub unsafe fn new(chunk: *mut u8, global: &'static XrossGlobalAllocator) -> Self {
        unsafe {
            let slabs = [
                LocalSlab::new(chunk.add(SLAB_OFFSETS[0])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[1])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[2])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[3])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[4])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[5])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[6])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[7])),
                LocalSlab::new(chunk.add(SLAB_OFFSETS[8])),
            ];
            let mut tlsf = Tlsf::new();
            let tlsf_slice =
                ptr::slice_from_raw_parts_mut(chunk.add(SLAB_TOTAL_CAPACITY), TLSF_CAPACITY)
                    as *mut [MaybeUninit<u8>];
            tlsf.insert_free_block(&mut *tlsf_slice);
            Self { slabs, tlsf, chunk_ptr: chunk, alloc_count: 0, global }
        }
    }

    #[inline(always)]
    pub fn get_slab_idx_from_offset(offset: usize) -> usize {
        offset >> 15
    }

    #[inline(never)]
    pub unsafe fn process_remote_frees(&mut self) {
        let chunk_idx = (self.chunk_ptr as usize - self.global.base_ptr as usize) / CHUNK_SIZE;
        let mut node =
            self.global.remote_free_queues[chunk_idx].head.swap(ptr::null_mut(), Ordering::Acquire);

        while !node.is_null() {
            unsafe {
                let next = (*(node as *mut crate::global::FreeNode)).next;
                let ptr = node as *mut u8;
                let offset = ptr as usize - self.chunk_ptr as usize;

                if offset < SLAB_TOTAL_CAPACITY {
                    self.slabs[Self::get_slab_idx_from_offset(offset)].dealloc(ptr);
                } else {
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
