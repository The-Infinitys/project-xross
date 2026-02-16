use crate::models::UnknownStruct;
use xross_core::{XrossClass, xross_class, xross_methods};

#[derive(Clone, XrossClass)]
#[xross(clonable)]
pub enum XrossTestEnum {
    A,
    B {
        #[xross_field]
        i: i32,
    },
    C {
        #[xross_field]
        j: Box<UnknownStruct>,
    },
}

#[derive(Clone, Copy, XrossClass)]
#[xross(clonable)]
pub enum XrossSimpleEnum {
    V,
    W,
    X,
    Y,
    Z,
}

#[xross_methods]
impl XrossSimpleEnum {
    #[xross_method]
    pub fn say_hello(&mut self) {
        println!("Hello, world!");
    }
}

pub enum UnClonable {
    S,
    Y,
    Z,
}

xross_class! {
    class struct UnClonable;
    is_clonable false;
}

#[derive(Clone)]
pub enum HelloEnum {
    A,
    B { i: i32 },
    C(Box<HelloEnum>),
    D,
}

xross_class! {
    package some;
    enum HelloEnum;
    variants {
        A;
        B {
            i: i32;
        }
        C(Box<HelloEnum>)
        D
    };
    clonable true;
    is_copy false;
}
