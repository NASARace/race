#![allow(unused)]

mod warp;

use std::{fs, io};
use std::error::Error;
use std::result::Result;
use std::path::{Path, PathBuf};

use gdal::{Dataset, Metadata};
use gdal::errors::*;
use gdal::{Driver,DriverManager};

use gdal_sys::{CPLErr, GDALResampleAlg};
use std::ptr::{null, null_mut};

use odin_common::macros::if_let;

pub struct GeoBoundingBox {
    west: f64,
    south: f64,
    east: f64,
    north: f64
}

pub fn extract_rect_to_file (src: &Path, tgt: &Path, bbox: &GeoBoundingBox) -> Result<(),Box<dyn Error>> {
    if_let! {
        Ok(src) = Dataset::open(src);
        Ok(tgt) = Dataset::open(tgt);

    }

    Ok(())
}
