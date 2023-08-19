#![allow(unused)]

pub mod warp;

#[macro_use]
extern crate lazy_static;

use std::collections::HashMap;
use std::ptr::{null, null_mut};
use std::ffi::{CString,CStr};
use libc::{c_void,c_char,c_uint, c_int};
use gdal::{Driver, DriverManager, Metadata, errors::GdalError, GeoTransform};
use gdal_sys::{CPLErrorReset, OGRErr, OSRExportToWkt, OSRNewSpatialReference, OSRSetFromUserInput, CPLErr};
use geo::{Rect};
use anyhow::{anyhow,Result,Error};

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

pub fn pc_char_to_string (pc_char: *const c_char) -> String {
    let cstr = unsafe { CStr::from_ptr(pc_char) };
    String::from_utf8_lossy(cstr.to_bytes()).to_string()
}

pub fn ok_true (cond: bool, err_msg: &'static str) -> Result<()> {
    if cond { Ok(()) } else { Err( anyhow!("{}",err_msg)) }
}

pub fn ok_not_zero (res: c_int, err_msg: &'static str) -> Result<()> {
    if res != 0 { Ok(()) } else { Err( anyhow!("{}",err_msg)) }
}

pub fn ok_non_null <R,T: ToString> (ptr: *const R, method_name: &'static str, msg: T) -> Result<*const R,GdalError> {
    if ptr != null() { return Ok(ptr) } else { Err(GdalError::NullPointer{ method_name, msg: msg.to_string() }) }
}

pub fn ok_mut_non_null <R,T: ToString> (ptr: *mut R, method_name: &'static str, msg: T) -> Result<*mut R,GdalError> {
    if ptr != null_mut() { return Ok(ptr) } else { Err(GdalError::NullPointer{ method_name, msg: msg.to_string() }) }
}

pub fn ok_ce_none (res: CPLErr::Type) -> Result<(),GdalError> {
    if res == CPLErr::CE_None { return Ok(()) } else { Err(last_cpl_err(res))}
}

pub fn last_cpl_err(cpl_err_class: CPLErr::Type) -> GdalError {
    let last_err_no = unsafe { gdal_sys::CPLGetLastErrorNo() };
    let last_err_msg = pc_char_to_string(unsafe { gdal_sys::CPLGetLastErrorMsg() });
    unsafe { gdal_sys::CPLErrorReset() };
    GdalError::CplError {
        class: cpl_err_class,
        number: last_err_no,
        msg: last_err_msg,
    }
}

pub fn new_geotransform (x_upper_left: f64, x_resolution: f64, row_rotation: f64,
                         y_upper_left: f64, col_rotation: f64, y_resolution: f64) -> GeoTransform {
    [x_upper_left,x_resolution,row_rotation,y_upper_left,col_rotation,y_resolution]
}

pub fn geotransform_from_bbox (bbox: Rect<f64>, x_resolution: f64, y_resolution: f64) -> GeoTransform {
    new_geotransform(bbox.min().x, x_resolution,0.0,
                     bbox.max().y, 0.0, y_resolution)
}