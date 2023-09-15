#![allow(unused)]

use std::error::Error;
use std::fs::File;
use std::path::PathBuf;

use anyhow::{anyhow,Result};

pub struct BoundingBox {
    west: f64,
    south: f64,
    east: f64,
    north: f64
}

pub enum DemImgType {
    PNG,
    TIF,
}

impl DemImgType {
    fn file_extension(&self) -> &'static str {
        match *self {
            DemImgType::PNG => "png",
            DemImgType::TIF => "tif",
        }
    }
}


fn get_filename (bbox: &BoundingBox, precision: usize, file_ext: &str) -> String {
    format!("dem_{:.precision$},{:.precision$},{:.precision$},{:.precision$}.{}",
            bbox.west, bbox.south, bbox.east, bbox.north, file_ext)
}

pub fn get_dem (bbox: &BoundingBox, epsg_srs: u32, img_type: DemImgType, cache_dir: &str) -> Result<File> {

    Err(anyhow!("failed to retrieve DEM"))
}
