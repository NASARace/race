
use gdal_sys::{CPLCalloc, GDALWarpAppOptions, GDALWarpAppOptionsNew};
use std::error::Error;
use std::result::Result;

use gdal::Dataset;
use std::ffi::{c_char, c_int, CString, OsStr};
use std::{
    borrow::Borrow,
    path::Path,
    ptr::{null, null_mut},
};

/// this is a builder object to provide the same functions as the gdalwarp executable.
/// Since the GDALWarpAppOptions struct is opaque (it is a C++ struct/class) we unfortunately have to translate
/// warp arguments into CStrings so that we can use GDALWarpAppOptionsNew() to initialize the
/// opaque structure.
/// TODO - this should be moved to GDALWarpOptions but this would be a lot more complex and fragile
///
/// currently supported options
///  -te_srs <srs_def>          : set target SRS (WKT,PROJ or EPSG: string)
///  -te <xmin ymin xmax ymax>  : set target extent to target SRS bounding box
/// ... many more to follow

pub struct Warp {
    srcs: Vec<String>,
    dst: String,
    opts: Vec<String>,
}

impl Warp {
    pub fn new (src: &Path, dst: &Path) -> Warp {
        unsafe {
            Warp {

            }
        }
    }

    pub fn run () -> Result<(),Box<dyn Error>> {

    }
}

impl Drop for Warp {
    fn drop (&mut self) {
        unsafe {
            if  self.c_opts != null {
                gdal_sys::GDALDestroyWarpOptions(self.c_opts);
            }
        }
    }
}
