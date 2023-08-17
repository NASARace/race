use std::ffi::CStr;
use std::fs;
use std::path::{Path, PathBuf};
use std::fs::*;
use std::ptr::null;
use gdal::{DriverManager,Dataset,DatasetOptions, GeoTransform, errors::*};
use gdal_sys::CPLErr::{Type,CE_None};
use geo::{Rect};
use anyhow::Result;


pub struct SimpleWarpBuilder {
    src_ds: Dataset,
    tgt_ds: Dataset,

}

impl SimpleWarpBuilder {
    pub fn new <P: AsRef<Path>>(src: P, tgt: P) -> Result<SimpleWarpBuilder> {
        if tgt.is_file() { fs::remove_file(tgt)? }

        let src_ds = Dataset::open(src)?;
        let tgt_ds = Dataset::open(tgt)?;

        SimpleWarpBuilder {
            src_ds,
            tgt_ds,
        }
    }

    pub fn set_tgt_bbox (bbox: &Rect<f64>) {

    }

    pub fn set_tgt_srs (srs: &SpatialRef) -> Result<()>{
        tgt_ds.set_spatial_ref(srs)
    }

    pub fn exec() -> Result<()> {

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

fn pc_char_to_string (pc_char: *const c_char) -> String {
    let cstr = unsafe { CStr::from_ptr(pc_char) };
    String::from_utf8_lossy(cstr.to_bytes()).to_string()
}

fn ok_non_null (ptr: *c_void, method_name: &str, msg: &str) -> Result<*c_void> {
    if ptr != null() { return Ok(ptr) } else { Err(GdalError::NullPointer{method_name,msg}) }
}

fn ok_ce_none (res: CPLErr::Type) -> Result<()> {
    if res == CPLErr::CE_None { return Ok(()) } else { Err(last_cpl_err(res))}
}

fn last_cpl_err(cpl_err_class: CPLErr::Type) -> GdalError::CplError {
    let last_err_no = unsafe { gdal_sys::CPLGetLastErrorNo() };
    let last_err_msg = pc_char_to_string(unsafe { gdal_sys::CPLGetLastErrorMsg() });
    unsafe { gdal_sys::CPLErrorReset() };
    GdalError::CplError {
        class: cpl_err_class,
        number: last_err_no,
        msg: last_err_msg,
    }
}

fn create_output_ds (src_ds: &Dataset,
                     filename: &str,
                     format: &str,
                     src_srs: &str,
                     tgt_srs: &str,
                     n_order: i64,
                     create_opts: Vec<String>,
                     pixel_resolutions: Option<(f64,f64)>,
                     bbox: &Rect<f64> ) -> Result<Dataset> {
    let mut n_pixels: c_int = 0;
    let mut n_lines: c_int = 0;
    let mut transform: GeoTransform = [0;6];
    let mut min_x: c_double = bbox.min().x;
    let mut min_y: c_double = bbox.min().y;
    let mut max_x: c_double = bbox.max().x;
    let mut max_y: c_double = bbox.max().y;

    let driver = gdal::DriverManager::get_driver_by_name(format)?;
    driver.metadata_item("DCAP_CREATE").ok_or( Err(errors::BadArgument(format.to_string())))?;

    let h_transform_arg = ok_non_null(unsafe {
        let c_src_srs = CString::new(src_srs)?;
        let c_tgt_srs = CString::new(tgt_srs)?;
        GDALCreateGenImgProjTransformer(src_ds.c_dataset(), c_src_srs.as_ptr(), null(), c_tgt_srs.as_ptr(),true, 1000.0, n_order)
    }, "GDALCreateGenImgProjTransformer", "failed to create transformer")?;

    ok_ce_none( unsafe {
        GDALSuggestedWarpOutput( src_ds.c_dataset(), GDALGenImgProjTransform, h_transform_arg, &transform, &n_pixels, &n_lines)
    })?;
    unsafe { GDALDestroyGenImgProjTransformer(h_transform_arg); }

    if let Some( (x_res,y_res) ) = pixel_resolutions {
        if min_x == 0.0 && min_y == 0.0 && max_x == 0.0 && max_y == 0.0 {
            min_x = transform[0];
            max_x = transform[0] + transform[1] * n_pixels;
            max_y = transform[3];
            min_y = transform[3] + transform[5] * n_lines;
        }

        n_pixels = (max_x - min_x + ())
    }
}