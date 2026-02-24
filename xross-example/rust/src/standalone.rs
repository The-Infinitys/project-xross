use xross_core::{xross_function, xross_function_dsl};

#[xross_function(package = "standalone")]
pub async fn async_add(a: i32, b: i32) -> i32 {
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    a + b
}

#[xross_function(package = "standalone")]
pub async fn async_greet(name: String) -> String {
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    format!("Async Hello, {}!", name)
}

#[xross_function(package = "standalone", critical)]
pub fn global_add(a: i32, b: i32) -> i32 {
    a + b
}

#[xross_function(package = "standalone")]
pub fn global_greet(name: String) -> String {
    format!("Hello, {}!", name)
}

#[xross_function(package = "standalone")]
#[xross_raw_function {
    sig = (a: i32, b: i32) -> i32;
    import = |a, b| { (a, b) };
    export = |res| { res };
}]
pub fn raw_global_add(a: i32, b: i32) -> i32 {
    a + b
}

#[xross_function(package = "standalone")]
#[xross_raw_function {
    sig = (a: i32, b: i32) -> i32;
    import = |a, b| { (a, b) };
    export = |res| { res };
    critical;
}]
pub fn raw_global_add_critical(a: i32, b: i32) -> i32 {
    a + b
}

#[xross_function(package = "standalone")]
#[xross_raw_function {
    sig = (should_panic: u8) -> *mut xross_core::XrossString;
    import = |should_panic| { should_panic };
    export = |res| { res };
    panicable;
}]
pub fn raw_global_panic(should_panic: u8) -> *mut xross_core::XrossString {
    if should_panic != 0 {
        panic!("Intentional panic from raw function!");
    }
    Box::into_raw(Box::new(xross_core::XrossString::from("Safe".to_string())))
}

#[xross_function(package = "standalone")]
pub fn test_unsigned(a: u8, b: u32, c: u64) -> u64 {
    (a as u64) + (b as u64) + c
}

xross_function_dsl! {
    package standalone;
    safety Atomic;
    fn global_multiply(a: i32, b: i32) -> i32;
}

pub fn global_multiply(a: i32, b: i32) -> i32 {
    a * b
}

#[xross_function(package = "standalone")]
pub fn heavy_prime_factorization(mut n: u64) -> u32 {
    let mut count = 0;
    while n.is_multiple_of(2) {
        count += 1;
        n /= 2;
    }
    while n.is_multiple_of(3) {
        count += 1;
        n /= 3;
    }
    let mut d = 5;
    while d * d <= n {
        while n.is_multiple_of(d) {
            count += 1;
            n /= d;
        }
        d += 2;
        while n.is_multiple_of(d) {
            count += 1;
            n /= d;
        }
        d += 4;
    }
    if n > 1 {
        count += 1;
    }
    count
}

#[xross_function(package = "standalone")]
pub fn heavy_matrix_multiplication(size: usize) -> f64 {
    let a = vec![1.1f64; size * size];
    let b = vec![2.2f64; size * size];
    let mut c = vec![0.0f64; size * size];

    for i in 0..size {
        let i_off = i * size;
        for k in 0..size {
            let k_off = k * size;
            let val_a = a[i_off + k];
            for j in 0..size {
                c[i_off + j] += val_a * b[k_off + j];
            }
        }
    }
    c[size * size - 1]
}

#[xross_function(package = "standalone")]
pub fn batch_heavy_prime_factorization(n: u64, repeat: u32) -> u32 {
    let mut last_count = 0;
    for _ in 0..repeat {
        last_count = heavy_prime_factorization(n);
    }
    last_count
}

#[xross_function(package = "standalone")]
pub fn get_large_array(size: usize) -> Vec<i32> {
    (0..size as i32).collect()
}

#[xross_function(package = "standalone")]
pub fn batch_heavy_matrix_multiplication(size: usize, repeat: u32) -> f64 {
    let a = vec![1.1f64; size * size];
    let b = vec![2.2f64; size * size];
    let mut c = vec![0.0f64; size * size];

    let mut last_res = 0.0;
    for _ in 0..repeat {
        c.fill(0.0);
        for i in 0..size {
            let i_off = i * size;
            for k in 0..size {
                let k_off = k * size;
                let val_a = a[i_off + k];
                for j in 0..size {
                    c[i_off + j] += val_a * b[k_off + j];
                }
            }
        }
        last_res = c[size * size - 1];
    }
    last_res
}
