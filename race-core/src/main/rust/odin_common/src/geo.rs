#![allow(unused)]

use num::{traits,zero};

// convoluted way around unstable trait alias
pub trait Num: traits::NumOps + traits::Zero + Copy + Send {}
impl <T: traits::NumOps + traits::Zero + Copy + Send> Num for T {}

#[repr(C)]
#[derive(Debug)]
pub struct BoundingBox <T: Num> {
    pub west: T,
    pub south: T,
    pub east: T,
    pub north: T
}

impl <T: Num> BoundingBox<T> {
    pub fn new() -> BoundingBox<T> {
        BoundingBox{
            west: zero::<T>(),
            south: zero::<T>(),
            east: zero::<T>(),
            north: zero::<T>()
        }
    }

    pub fn from_wsen<N> (wsen: &[N;4]) -> BoundingBox<T> where N: Num + Into<T> {
        BoundingBox::<T>{
            west: wsen[0].into(),
            south: wsen[1].into(),
            east: wsen[2].into(),
            north: wsen[3].into()
        }
    }

    pub fn to_minmax_array (&self) -> [T;4] {
        [self.west,self.south,self.east,self.north]
    }

    pub fn as_mimax_array_ref (&self) -> &[T;4] {
        unsafe { std::mem::transmute(self) }
    }
}

