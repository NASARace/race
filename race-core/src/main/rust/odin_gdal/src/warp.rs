use std::ffi::{CString,CStr};
use std::path::{Path, PathBuf};
use std::fs;
use std::ptr::{null,null_mut};
use std::error::Error;
use gdal::{DriverManager,Dataset,DatasetOptions, GeoTransform};
use gdal::cpl::CslStringList;
use gdal::spatial_ref::SpatialRef;
use gdal_sys::{GDALDatasetH,OGRSpatialReferenceH,
               GDALCreateGenImgProjTransformer,GDALGenImgProjTransform,GDALCreateApproxTransformer,GDALApproxTransform,
               GDALTransformerFunc, GDALSimpleImageWarp,
               GDALDestroyApproxTransformer, GDALDestroyGenImgProjTransformer,
               GDALClose, GDALFlushCache,
};
use libc::{c_void,c_char,c_int, c_double};
use anyhow::{anyhow,Result};
use crate::{ok_non_null, ok_mut_non_null, ok_not_zero};


// what we get from warpshim.cpp
extern "C" {
    fn CPLFree (p: *mut c_void);
    fn SanitizeSRS (p: *const c_char) -> *const c_char;
    fn GDALWarpCreateOutput(
        hSrcDS: GDALDatasetH ,
        pszFilename: *const c_char,
        pszFormat: *const c_char,
        pszSourceSRS:  *const c_char,
        pszTargetSRS:  *const c_char,
        nOrder: c_int,
        papszCreateOptions: *mut *mut c_char,
        dfMinX: c_double, dfMaxX: c_double, dfMinY: c_double, dfMaxY: c_double,
        dfXRes: c_double, dfYRes: c_double,
        nForcePixels: c_int, nForceLines: c_int
    ) -> GDALDatasetH;
}

pub struct SimpleWarpBuilder <'a> {
    src_ds: &'a Dataset,
    tgt_filename: CString,

    min_x: c_double,
    max_x: c_double,
    min_y: c_double,
    max_y: c_double,

    res_x: c_double,
    res_y: c_double,

    force_n_lines: c_int,
    force_n_pixels: c_int,

    tgt_srs: Option<&'a SpatialRef>,
    tgt_format: Option<CString>,
    create_options: Option<&'a CslStringList>,
    src_srs: Option<&'a SpatialRef>,
    n_order: c_int,
    error_threshold: c_double,
}

impl <'a> SimpleWarpBuilder<'a> {
    pub fn new <P: AsRef<Path>>(src_ds: &'a Dataset, tgt: P) -> Result<SimpleWarpBuilder<'a>,Box<dyn Error>> {
        let path = tgt.as_ref();
        let tgt_str = path.to_str().ok_or(anyhow!("{:?} not a valid filename", tgt.as_ref()))?;
        let tgt_filename = CString::new(tgt_str)?;

        Ok(SimpleWarpBuilder {
            src_ds,
            tgt_filename,

            min_x: 0.0, max_x: 0.0, min_y: 0.0, max_y: 0.0,
            res_x: 0.0, res_y: 0.0,
            force_n_lines: 0, force_n_pixels: 0,

            tgt_srs: None,
            tgt_format: None,
            create_options: None,
            src_srs: None,
            n_order: 0,
            error_threshold: 0.125  // ?? sounds like a fortytwo
        })
    }

    pub fn set_tgt_extent (&mut self, min_x: f64, min_y: f64, max_x: f64, max_y: f64) -> &mut SimpleWarpBuilder<'a> {
        self.min_x = min_x;
        self.max_x = max_x;
        self.min_y = min_y;
        self.max_y = max_y;
        self
    }

    pub fn set_tgt_resolution (&mut self, res_x: f64, res_y: f64) -> &mut SimpleWarpBuilder<'a> {
        self.res_x = res_x;
        self.res_y = res_y;
        self
    }

    pub fn set_tgt_size (&mut self, npixels: i32, nlines: i32) -> &mut SimpleWarpBuilder<'a> {
        self.force_n_pixels = npixels;
        self.force_n_lines = nlines;
        self
    }

    pub fn set_tgt_srs (&mut self, srs: &'a SpatialRef) -> &mut SimpleWarpBuilder<'a> {
        self.tgt_srs = Some(srs);
        self
    }

    pub fn set_src_srs (&mut self, srs: &'a SpatialRef) -> &mut SimpleWarpBuilder<'a> {
        self.src_srs = Some(srs);
        self
    }

    pub fn set_tgt_format (&mut self, tgt_format: &str) -> Result<&mut SimpleWarpBuilder<'a>> {
        self.tgt_format = Some(CString::new(tgt_format)?);
        Ok(self)
    }

    pub fn set_create_options (&mut self, create_options: &'a CslStringList) -> &mut SimpleWarpBuilder<'a> {
        self.create_options = Some(create_options);
        self
    }

    pub fn set_axis_order (&mut self, order: i32) -> &mut SimpleWarpBuilder<'a> {
        self.n_order = order;
        self
    }

    pub fn set_error_threshold (&mut self, error_threshold: f64) -> &mut SimpleWarpBuilder<'a> {
        self.error_threshold = error_threshold;
        self
    }

    pub fn exec(&self) -> Result<Dataset> {
        // get source SRS ref (either set or from src_ds)
        let src_ds_srs = if let Ok(srs) = self.src_ds.spatial_ref() { srs } else {
            self.src_ds.gcp_spatial_ref().ok_or(anyhow!("no spatial reference for source"))?
        };
        let src_srs = if let Some(srs_ref) = self.src_srs { srs_ref } else { &src_ds_srs };
        let src_wkt = CString::new(src_srs.to_wkt()?)?;
        let tgt_srs = if let Some(srs_ref) = self.tgt_srs { srs_ref } else { src_srs };
        let tgt_wkt = CString::new(tgt_srs.to_wkt()?)?;
        let tgt_format = if let Some(format) = &self.tgt_format { format.as_ptr() } else { null() };
        let c_create_options = if let Some(sl) = self.create_options { sl.as_ptr() } else { null_mut() };

        // check if output file exists and if so delete it
        let path = Path::new(self.tgt_filename.to_str()?);
        if path.is_file() { fs::remove_file(path)? }

        unsafe {
            let c_tgt_ds: GDALDatasetH = GDALWarpCreateOutput(
                        self.src_ds.c_dataset(),
                        self.tgt_filename.as_ptr(),
                        tgt_format,
                        src_wkt.as_ptr(),
                        tgt_wkt.as_ptr(),
                        self.n_order,
                        c_create_options,
                        self.min_x, self.max_x, self.min_y, self.max_y,
                        self.res_x, self.res_y,
                        self.force_n_pixels, self.force_n_lines);
            if c_tgt_ds == null_mut() {
                return Err(anyhow!("GDALWarpCreateOutput failed"))
            }

            let c_gen_transformer_arg= gdal_sys::GDALCreateGenImgProjTransformer(
                        self.src_ds.c_dataset(),
                        src_wkt.as_ptr(),
                        c_tgt_ds,
                        tgt_wkt.as_ptr(),
                        1, 1000.0, self.n_order);
            if c_gen_transformer_arg == null_mut() {
                gdal_sys::GDALClose(c_tgt_ds);
                return Err(anyhow!("GDALCreateGenImgProjTransformer failed"))
            }

            let mut c_transformer_arg = c_gen_transformer_arg;
            let mut c_transformer_func: gdal_sys::GDALTransformerFunc = Some(gdal_sys::GDALGenImgProjTransform);
            let mut c_approx_transformer_arg: *mut c_void = null_mut();

            if self.error_threshold != 0.0 {
                c_approx_transformer_arg = gdal_sys::GDALCreateApproxTransformer(
                        c_transformer_func,
                        c_gen_transformer_arg,
                        self.error_threshold);
                if c_approx_transformer_arg == null_mut() {
                    gdal_sys::GDALDestroyGenImgProjTransformer(c_gen_transformer_arg);
                    gdal_sys::GDALClose(c_tgt_ds);
                    return Err(anyhow!("GDALCreateApproxTransformer failed"))
                }

                c_transformer_arg = c_approx_transformer_arg;
                c_transformer_func = Some(gdal_sys::GDALApproxTransform);
            }

            let mut c_warp_opts = CslStringList::new();
            c_warp_opts.set_name_value("INIT","0")?; // that should not fail since it's all constants

            let res = gdal_sys::GDALSimpleImageWarp(
                    self.src_ds.c_dataset(),
                    c_tgt_ds,
                    0, null_mut(),
                    c_transformer_func, c_transformer_arg,
                    None, null_mut(),
                    c_warp_opts.as_ptr());

            if c_approx_transformer_arg != null_mut() {
                gdal_sys::GDALDestroyApproxTransformer(c_approx_transformer_arg);
            }
            gdal_sys::GDALDestroyGenImgProjTransformer(c_gen_transformer_arg);

            if res != 0 {
                gdal_sys::GDALFlushCache(c_tgt_ds);
                Ok(Dataset::from_c_dataset(c_tgt_ds))
            } else {
                gdal_sys::GDALClose(c_tgt_ds);
                Err(anyhow!("GDALSimpleImageWarp failed"))
            }
        }
    }
}
