use std::sync::atomic::AtomicIsize;

pub static SERVICE_COUNT: AtomicIsize = AtomicIsize::new(0);
pub static UNKNOWN_STRUCT_COUNT: AtomicIsize = AtomicIsize::new(0);
pub static SERVICE2_COUNT: AtomicIsize = AtomicIsize::new(0);

pub static RUNTIME: std::sync::LazyLock<tokio::runtime::Runtime> = std::sync::LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Failed to create Tokio runtime")
});
