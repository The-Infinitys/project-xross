pub mod global;
pub mod heap;
pub mod local;
pub mod slab;

use crate::global::{CHUNK_SIZE, XrossGlobalAllocator};
use crate::heap::heap;
use crate::local::XrossLocalAllocator;
use crate::slab::{SLAB_TOTAL_CAPACITY, SlabLayout};
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::UnsafeCell;
use std::ptr::{self, NonNull};
use std::sync::OnceLock;

const LARGE_THRESHOLD: usize = CHUNK_SIZE / 2;

pub struct XrossAlloc;

static XROSS_GLOBAL_ALLOC: OnceLock<XrossGlobalAllocator> = OnceLock::new();

#[inline(always)]
fn xross_global_alloc() -> &'static XrossGlobalAllocator {
    XROSS_GLOBAL_ALLOC.get_or_init(|| {
        let h = heap();
        XrossGlobalAllocator::new(h.ptr, h.size)
    })
}

thread_local! {
    static LOCAL_ALLOC: UnsafeCell<Option<XrossLocalAllocator>> = const { UnsafeCell::new(None) };
}

#[inline(always)]
fn get_slab_idx(size: usize) -> usize {
    if size <= 8 {
        return 0;
    }
    (usize::BITS - (size - 1).leading_zeros()) as usize - 3
}

unsafe impl GlobalAlloc for XrossAlloc {
    #[inline]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let size = layout.size();
        if size <= SlabLayout::CHUNK_THRESHOLD {
            return LOCAL_ALLOC.with(|cell| {
                let local_ptr = cell.get();
                unsafe {
                    if (*local_ptr).is_none() {
                        let g = xross_global_alloc();
                        let chunk = g.alloc_chunk();
                        if chunk.is_null() {
                            return System.alloc(layout);
                        }
                        ptr::write(local_ptr, Some(XrossLocalAllocator::new(chunk, g)));
                    }
                    let local = (*local_ptr).as_mut().unwrap_unchecked();

                    local.alloc_count = local.alloc_count.wrapping_add(1);
                    if local.alloc_count & 63 == 0 {
                        local.process_remote_frees();
                    }

                    let idx = get_slab_idx(size);
                    let p = local.slabs[idx].alloc(idx);
                    if !p.is_null() {
                        p
                    } else {
                        local
                            .tlsf
                            .allocate(layout)
                            .map_or_else(|| System.alloc(layout), |p| p.as_ptr())
                    }
                }
            });
        }

        if size > LARGE_THRESHOLD {
            unsafe {
                return System.alloc(layout);
            }
        }

        LOCAL_ALLOC.with(|cell| {
            let local_ptr = cell.get();
            unsafe {
                if let Some(local) = (*local_ptr).as_mut() {
                    local.tlsf.allocate(layout).map_or_else(|| System.alloc(layout), |p| p.as_ptr())
                } else {
                    System.alloc(layout)
                }
            }
        })
    }

    #[inline]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        if ptr.is_null() {
            return;
        }
        let g = xross_global_alloc();

        if !g.is_own(ptr) {
            unsafe {
                System.dealloc(ptr, layout);
            }
            return;
        }

        LOCAL_ALLOC.with(|cell| {
            let local_ptr = cell.get();
            unsafe {
                if let Some(local) = (*local_ptr).as_mut() {
                    let addr = ptr as usize;
                    let base = local.chunk_ptr as usize;
                    let off = addr.wrapping_sub(base);
                    if off < CHUNK_SIZE {
                        if off < SLAB_TOTAL_CAPACITY {
                            local.slabs[XrossLocalAllocator::get_slab_idx_from_offset(off)]
                                .dealloc(ptr);
                        } else {
                            local.tlsf.deallocate(NonNull::new_unchecked(ptr), layout.align());
                        }
                        return;
                    }
                }
                g.push_remote_free(ptr);
            }
        });
    }

    #[inline]
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        if ptr.is_null() {
            unsafe {
                return self.alloc(Layout::from_size_align_unchecked(new_size, layout.align()));
            }
        }
        if new_size == 0 {
            unsafe {
                self.dealloc(ptr, layout);
            }
            return ptr::null_mut();
        }

        let g = xross_global_alloc();
        if !g.is_own(ptr) {
            unsafe {
                return System.realloc(ptr, layout, new_size);
            }
        }

        let old_size = layout.size();
        if old_size <= 2048 && new_size <= 2048 {
            let old_idx = get_slab_idx(old_size);
            let new_idx = get_slab_idx(new_size);

            if old_idx == new_idx {
                return ptr;
            }
        }

        unsafe {
            let new_layout = Layout::from_size_align_unchecked(new_size, layout.align());
            let new_ptr = self.alloc(new_layout);
            if !new_ptr.is_null() {
                ptr::copy_nonoverlapping(ptr, new_ptr, old_size.min(new_size));
                self.dealloc(ptr, layout);
            }
            new_ptr
        }
    }
}
