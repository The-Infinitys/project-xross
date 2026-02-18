use jemallocator::Jemalloc;
use mimalloc::MiMalloc;
use rand::prelude::*;
use rpmalloc::RpMalloc;
use snmalloc_rs::SnMalloc;
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};
use tcmalloc::TCMalloc;
use xross_alloc::XrossAlloc;

const THREADS: usize = 32;
const OPS_PER_THREAD: usize = 200_000;

const ITERS: usize = 100; // 試行回数
const BENCH_TIMEOUT: Duration = Duration::from_secs(3); // 1項目あたりの最大時間

struct SendPtr(*mut u8);
unsafe impl Send for SendPtr {}

type Scenario = (&'static str, fn(&'static (dyn GlobalAlloc + Sync)));
fn main() {
    let xross = Box::leak(Box::new(XrossAlloc));
    let sys = Box::leak(Box::new(System));

    let mi = Box::leak(Box::new(MiMalloc));
    let je = Box::leak(Box::new(Jemalloc));
    let tc = Box::leak(Box::new(TCMalloc));
    let sn = Box::leak(Box::new(SnMalloc));
    let rpm = Box::leak(Box::new(RpMalloc));

    let scenarios: [Scenario; 9] = [
        ("Scenario 1: Burst", burst_alloc_dealloc),
        ("Scenario 2: Mixed", mixed_size_alloc),
        ("Scenario 3: Shuffle", random_shuffle_alloc),
        ("Scenario 4: Locality", locality_sum_bench),
        ("Scenario 5: Frag", fragmentation_bench),
        ("Scenario 6: Prod-Cons", producer_consumer_bench),
        ("Scenario 7: Large Allocs", large_alloc_bench),
        ("Scenario 8: Realloc Stress", realloc_stress_bench),
        ("Scenario 9: Tiny Allocs", tiny_alloc_bench),
    ];
    for (name, func) in scenarios {
        println!("{}", name);
        run_averaged_bench("sys", sys, func);
        run_averaged_bench("Xross", xross, func);

        #[cfg(not(target_os = "macos"))]
        {
            run_averaged_bench("mimalloc", mi, func);
            run_averaged_bench("JE", je, func);
            run_averaged_bench("tc", tc, func);
            run_averaged_bench("Sn", sn, func);
            run_averaged_bench("RPM", rpm, func);
        }
        println!();
    }
}

fn run_averaged_bench<A: GlobalAlloc + 'static + Sync>(
    name: &str,
    alloc: &'static A,
    f: fn(&'static (dyn GlobalAlloc + Sync)),
) {
    let mut results = Vec::with_capacity(ITERS);
    let total_start = Instant::now();

    for _ in 0..ITERS {
        if total_start.elapsed() > BENCH_TIMEOUT && !results.is_empty() {
            break;
        }

        let start = Instant::now();
        let mut handles = vec![];

        for _ in 0..THREADS {
            handles.push(thread::spawn(move || {
                f(alloc);
            }));
        }
        for h in handles {
            h.join().unwrap();
        }

        let duration = start.elapsed();
        let total_ops = THREADS * OPS_PER_THREAD;
        results.push((duration.as_nanos() as f64) / (total_ops as f64));
    }

    if results.is_empty() {
        println!("  {:<15}: Skipped (Timeout)", name);
        return;
    }

    let actual_iters = results.len();
    results.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let avg: f64 = results.iter().sum::<f64>() / (actual_iters as f64);
    let min = results[0];

    print!("  {:<15}: {:>10.2} ns/op (min: {:>6.2} ns/op)", name, avg, min);
    if actual_iters < ITERS {
        print!(" [{} iters]", actual_iters);
    }
    println!();
}

fn burst_alloc_dealloc(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let mut ptrs = Vec::with_capacity(1000);
    for _ in 0..(OPS_PER_THREAD / 1000) {
        for _ in 0..1000 {
            unsafe {
                ptrs.push(alloc.alloc(layout));
            }
        }
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

fn mixed_size_alloc(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let sizes = [64, 128, 256, 1024];
    let layouts: Vec<_> = sizes.iter().map(|&s| Layout::from_size_align(s, 16).unwrap()).collect();

    for i in 0..OPS_PER_THREAD {
        let layout = layouts[i % 4];
        unsafe {
            let ptr = alloc.alloc(layout);
            alloc.dealloc(ptr, layout);
        }
    }
}

fn random_shuffle_alloc(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let mut rng = StdRng::seed_from_u64(42);
    let chunk_size = 500;
    let mut ptrs = Vec::with_capacity(chunk_size);

    for _ in 0..(OPS_PER_THREAD / chunk_size) {
        (0..chunk_size).for_each(|_| unsafe {
            ptrs.push(alloc.alloc(layout));
        });
        ptrs.shuffle(&mut rng);
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

fn locality_sum_bench(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let count = 1000;
    let mut ptrs = Vec::with_capacity(count);

    for _ in 0..(OPS_PER_THREAD / count) {
        for i in 0..count {
            unsafe {
                let ptr = alloc.alloc(layout);
                ptr.write(i as u8);
                ptrs.push(ptr);
            }
        }

        let mut sum: u64 = 0;
        for ptr in &ptrs {
            unsafe {
                sum += (**ptr) as u64;
            }
        }
        std::hint::black_box(sum);

        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

fn fragmentation_bench(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let persistent_count = 1500;
    let temp_count = 500;

    unsafe {
        let mut persistent = Vec::with_capacity(persistent_count);
        for _ in 0..persistent_count {
            persistent.push(alloc.alloc(layout));
        }

        for _ in 0..(OPS_PER_THREAD / temp_count) {
            let mut temps = Vec::with_capacity(temp_count);
            for _ in 0..temp_count {
                temps.push(alloc.alloc(layout));
            }
            for ptr in temps {
                alloc.dealloc(ptr, layout);
            }
        }

        for ptr in persistent {
            alloc.dealloc(ptr, layout);
        }
    }
}

fn producer_consumer_bench(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let (tx, rx) = mpsc::sync_channel::<SendPtr>(1000);

    let handle = thread::spawn(move || {
        for _ in 0..OPS_PER_THREAD {
            if let Ok(ptr) = rx.recv() {
                unsafe { alloc.dealloc(ptr.0, layout); }
            }
        }
    });

    for _ in 0..OPS_PER_THREAD {
        unsafe {
            let ptr = alloc.alloc(layout);
            tx.send(SendPtr(ptr)).unwrap();
        }
    }
    
    handle.join().unwrap();
}

fn large_alloc_bench(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let iterations = 100; 
    let scale = OPS_PER_THREAD / iterations;
    
    for _ in 0..iterations {
        let size = 1024 * 1024 * 2; // 2MB
        let layout = Layout::from_size_align(size, 4096).unwrap();
        unsafe {
            let ptr = alloc.alloc(layout);
            ptr.write(0xFF);
            for _ in 0..scale { std::hint::black_box(()); } 
            alloc.dealloc(ptr, layout);
        }
    }
}

fn realloc_stress_bench(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let mut layout = Layout::from_size_align(64, 16).unwrap();
    for _ in 0..(OPS_PER_THREAD / 10) {
        unsafe {
            let mut ptr = alloc.alloc(layout);
            for i in 1..=10 {
                let new_size = 64 * i;
                let new_layout = Layout::from_size_align(new_size, 16).unwrap();
                ptr = alloc.realloc(ptr, layout, new_size);
                layout = new_layout;
            }
            alloc.dealloc(ptr, layout);
            layout = Layout::from_size_align(64, 16).unwrap();
        }
    }
}

fn tiny_alloc_bench(alloc: &'static (dyn GlobalAlloc + Sync)) {
    let layout = Layout::from_size_align(8, 8).unwrap();
    let mut ptrs = Vec::with_capacity(1000);
    for _ in 0..(OPS_PER_THREAD / 1000) {
        for _ in 0..1000 {
            unsafe {
                ptrs.push(alloc.alloc(layout));
            }
        }
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}


