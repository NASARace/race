#[macro_use]
extern crate lazy_static;

use std::collections::HashMap;
use std::ptr::{null, null_mut};
use std::ffi::{CString};
use gdal::{Driver, DriverManager, Metadata};
use gdal_sys::{CPLErrorReset, OGRErr, OSRExportToWkt, OSRNewSpatialReference, OSRSetFromUserInput};
use odin_common::fs::*;

pub trait DriverExt {
    fn get_driver_name (&self) -> String;
    fn get_driver_extensions (&self) -> Vec<String>;
}

impl DriverExt for Driver {
    fn get_driver_name (&self) -> String {
        self.metadata_item("DMD_LONGNAME", "").unwrap() // there always is one
    }

    fn get_driver_extensions (&self) -> Vec<String> {
        if let Some(s) = self.metadata_item("DMD_EXTENSIONS", "") {
            s.split(' ').map(|x| x.trim().to_string()).collect()
        } else {
            vec!()
        }
    }
}

lazy_static! {
    static ref EXTS: HashMap<String,usize> = new_file_extension_map();
}

pub fn initialize_gdal() -> bool {
    EXTS.len() > 0
}

fn new_file_extension_map () -> HashMap<String,usize> { // file extension -> driver #
    DriverManager::register_all();
    let mut em = HashMap::new();

    let count = DriverManager::count();
    for i in 0..count {
        if let Ok(driver) = DriverManager::get_driver(i) {
            for ext in driver.get_driver_extensions() {
                em.insert(ext, i);
            }
        }
    }

    em
}

pub fn get_raster_driver_from_filename (filename: &str) -> Option<gdal::Driver> {
    get_filename_extension(filename)
        .and_then( |ext| EXTS.get( ext))
        .and_then( |n| DriverManager::get_driver(*n).ok())
}
