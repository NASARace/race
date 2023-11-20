use std::ffi::{CString,CStr};
use std::path::{Path, PathBuf};
use std::fs;
use std::ptr::{null,null_mut};
use std::error::Error;
use gdal::{DriverManager,Dataset,DatasetOptions, GeoTransform};
use gdal::cpl::CslStringList;
use gdal::spatial_ref::SpatialRef;
use gdal_sys::{GDALDatasetH, GDALProgressFunc, GDALWarpOptions, CPLErr::CE_None, CPLErr};
use libc::{c_void,c_char,c_int, c_double};
use odin_common::geo::BoundingBox;
use crate::{ok_non_null, ok_mut_non_null, ok_not_zero, ok_ce_none};
use crate::errors::{Result,last_gdal_error, misc_error, OdinGdalError, reset_last_gdal_error};

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

    axis_order: c_int,
    max_error: c_double,
}

impl <'a> SimpleWarpBuilder<'a> {
    pub fn new <P: AsRef<Path>>(src_ds: &'a Dataset, tgt: P) -> Result<SimpleWarpBuilder<'a>> {
        let path = tgt.as_ref();
        let tgt_str = path.to_str().ok_or(OdinGdalError::InvalidFileName(path.display().to_string()))?;
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
            axis_order: 0,
            max_error: 0.0,
        })
    }

    pub fn set_tgt_extent (&mut self, min_x: f64, min_y: f64, max_x: f64, max_y: f64) -> &mut SimpleWarpBuilder<'a> {
        self.min_x = min_x;
        self.max_x = max_x;
        self.min_y = min_y;
        self.max_y = max_y;
        self
    }

    pub fn set_tgt_extent_from_bbox (&mut self, bbox: &BoundingBox<f64>) ->  &mut SimpleWarpBuilder<'a> {
        self.min_x = bbox.west;
        self.max_x = bbox.east;
        self.min_y = bbox.south;
        self.max_y = bbox.north;
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
        self.axis_order = order;
        self
    }

    pub fn set_max_error (&mut self, max_error: f64) -> &mut SimpleWarpBuilder<'a> {
        self.max_error = max_error;
        self
    }

    // version without C shim functions

    pub fn exec(&self) -> Result<Dataset> {
        let tgt_ds = self.create_tgt_ds()?;
        self.chunk_and_warp(&tgt_ds).map(|_| tgt_ds)
    }

    fn create_tgt_ds (&self) -> Result<Dataset> {
        unsafe {
            reset_last_gdal_error();

            let c_src_ds = self.src_ds.c_dataset();
            let src_ds_srs = self.src_ds.spatial_ref().ok().or_else(|| self.src_ds.gcp_spatial_ref());
            let src_srs = self.src_srs.or_else(|| src_ds_srs.as_ref()).ok_or(OdinGdalError::NoSpatialReferenceSystem)?;

            let src_wkt = CString::new(src_srs.to_wkt()?)?;
            let tgt_srs = if let Some(srs_ref) = self.tgt_srs { srs_ref } else { src_srs };
            let tgt_wkt = CString::new(tgt_srs.to_wkt()?)?;

            let tgt_format = if let Some(format) = &self.tgt_format { format.as_ptr() } else { null() };
            let c_create_options = if let Some(sl) = self.create_options { sl.as_ptr() } else { null_mut() };

            // check if output file exists and if so delete it
            let path = Path::new(self.tgt_filename.to_str().unwrap()); // already checked during new()
            if path.is_file() { fs::remove_file(path)? }

            let c_driver = gdal_sys::GDALGetDriverByName(tgt_format);
            if c_driver == null_mut() {
                return Err(misc_error(format!("unknown output format {:?}", self.tgt_format)))
            }

            let mut geo_transform: [c_double; 6] = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
            let mut n_pixels: c_int = 0;
            let mut n_lines: c_int = 0;

            let c_transform_arg = ok_mut_non_null(
                gdal_sys::GDALCreateGenImgProjTransformer(c_src_ds, src_wkt.as_ptr(), null_mut(), tgt_wkt.as_ptr(), 1, 0.0, 0),
                || "GDALCreateGenImgProjTransformer() failed".to_string())?;

            if CE_None != gdal_sys::GDALSuggestedWarpOutput(c_src_ds, Some(gdal_sys::GDALGenImgProjTransform), c_transform_arg,
                                                            geo_transform.as_mut_ptr(), &mut n_pixels as *mut c_int, &mut n_lines as *mut c_int) {
                gdal_sys::GDALDestroyGenImgProjTransformer(c_transform_arg);
                return Err(last_gdal_error())
            }

            let mut res_x = self.res_x;
            let mut res_y = self.res_y;
            let mut min_x = self.min_x;
            let mut max_x = self.max_x;
            let mut min_y = self.min_y;
            let mut max_y = self.max_y;

            if res_x != 0.0 && res_y != 0.0 { // explicitly given pixel resolution
                if n_pixels != 0 || n_lines != 0 {
                    gdal_sys::GDALDestroyGenImgProjTransformer(c_transform_arg);
                    return Err(OdinGdalError::MiscError("cannot specify dimensions and resolution for warped dataset".to_string()))
                }
                if min_x == 0.0 && min_y == 0.0 && max_x == 0.0 && max_y == 0.0 {
                    min_x = geo_transform[0];
                    max_x = geo_transform[0] + geo_transform[1] * n_pixels as c_double;
                    max_y = geo_transform[3];
                    min_y = geo_transform[3] + geo_transform[5] * n_lines as c_double;
                }

                n_pixels = ((max_x - min_x + (res_x / 2.0)) / res_x).to_int_unchecked();
                n_lines = ((max_y - min_y + (res_y / 2.0)) / res_y).to_int_unchecked();
                geo_transform[0] = min_x;
                geo_transform[3] = max_y;
                geo_transform[1] = res_x;
                geo_transform[5] = -res_y;
            } else if self.force_n_pixels != 0 && self.force_n_lines != 0 { // explicitly given n_pixels, n_lines
                if min_x == 0.0 && min_y == 0.0 && max_x == 0.0 && max_y == 0.0 {
                    min_x = geo_transform[0];
                    max_x = geo_transform[0] + geo_transform[1] * n_pixels as c_double;
                    max_y = geo_transform[3];
                    min_y = geo_transform[3] + geo_transform[5] * n_lines as c_double;
                }

                res_x = (max_x - min_x) / self.force_n_pixels as c_double;
                res_y = (max_y - min_y) / self.force_n_lines as c_double;

                geo_transform[0] = min_x;
                geo_transform[3] = max_y;
                geo_transform[1] = res_x;
                geo_transform[5] = -res_y;

                n_pixels = self.force_n_pixels;
                n_lines = self.force_n_lines;
            } else if min_x != 0.0 || min_y != 0.0 || max_x != 0.0 || max_y != 0.0 { // explicitly given min/max values
                res_x = geo_transform[1];
                res_y = geo_transform[5].abs();

                n_pixels = ((max_x - min_x + (res_x / 2.0)) / res_y).to_int_unchecked();
                n_lines = ((max_y - min_y + (res_y / 2.0)) / res_y).to_int_unchecked();

                geo_transform[0] = min_x;
                geo_transform[3] = max_y;
            }

            let n_bands = gdal_sys::GDALGetRasterCount(c_src_ds);

            let c_tgt_ds = gdal_sys::GDALCreate(c_driver, self.tgt_filename.as_ptr(), n_pixels, n_lines,
                                                n_bands, gdal_sys::GDALGetRasterDataType(gdal_sys::GDALGetRasterBand(c_src_ds, 1)), c_create_options);
            if c_tgt_ds == null_mut() {
                let last_error = last_gdal_error();
                gdal_sys::GDALDestroyGenImgProjTransformer(c_transform_arg);
                return Err(last_error)
            }

            gdal_sys::GDALDestroyGenImgProjTransformer(c_transform_arg);

            gdal_sys::GDALSetProjection(c_tgt_ds, tgt_wkt.as_ptr());
            gdal_sys::GDALSetGeoTransform(c_tgt_ds, &mut geo_transform as *mut c_double);

            // preserve no-data values and color tables
            for i in 1..=n_bands {
                let c_src_band = gdal_sys::GDALGetRasterBand(c_src_ds, i);
                let nv = gdal_sys::GDALGetRasterNoDataValue(c_src_band, null_mut());

                let c_tgt_band = gdal_sys::GDALGetRasterBand(c_tgt_ds, i);
                gdal_sys::GDALSetRasterNoDataValue(c_tgt_band, nv);

                let c_color_tbl = gdal_sys::GDALGetRasterColorTable(c_src_band);
                if c_color_tbl != null_mut() {
                    gdal_sys::GDALSetRasterColorTable(c_tgt_band, c_color_tbl);
                }
            }

            Ok(Dataset::from_c_dataset(c_tgt_ds))
        }
    }

    fn chunk_and_warp (&self, tgt_ds: &Dataset) -> Result<()> {
        unsafe {
            reset_last_gdal_error();

            let c_src_ds = self.src_ds.c_dataset();
            let c_tgt_ds = tgt_ds.c_dataset();

            let n_bands = self.src_ds.raster_count() as usize;
            if n_bands == 0 {
                gdal_sys::GDALClose(c_tgt_ds);
                return Err(OdinGdalError::MiscError("no raster bands in input".to_string()))
            }

            let c_warp_options = gdal_sys::GDALCreateWarpOptions();
            let warp_options: &mut GDALWarpOptions = c_warp_options.as_mut().ok_or(last_gdal_error())?;
            warp_options.hSrcDS = self.src_ds.c_dataset();
            warp_options.hDstDS = c_tgt_ds;

            // process all bands
            warp_options.nBandCount = n_bands as c_int;
            // note we need to allocate this with CPLMalloc since it is freed by GDAL
            let c_src_bands = gdal_sys::CPLMalloc(std::mem::size_of::<c_int>() * n_bands) as *mut c_int;
            let c_tgt_bands = gdal_sys::CPLMalloc(std::mem::size_of::<c_int>() * n_bands) as *mut c_int;
            for i in 0..n_bands as isize {
                let band_no = (i+1) as c_int;
                *(c_src_bands.offset(i)) = band_no;
                *(c_tgt_bands.offset(i)) = band_no;
            }
            warp_options.panSrcBands = c_src_bands;
            warp_options.panDstBands = c_tgt_bands;

            warp_options.pfnProgress = Some(gdal_sys::GDALDummyProgress);
            //warp_options.pProgressArg = null_mut();

            //--- proj transformers
            let c_gen_transformer_arg= gdal_sys::GDALCreateGenImgProjTransformer(
                self.src_ds.c_dataset(),
                gdal_sys::GDALGetProjectionRef(self.src_ds.c_dataset()),
                c_tgt_ds,
                gdal_sys::GDALGetProjectionRef(c_tgt_ds),
                0, 0.0, 0
            );
            if c_gen_transformer_arg == null_mut() {
                gdal_sys::GDALClose(c_tgt_ds);
                return Err(last_gdal_error())
            }

            let mut c_transformer_arg = c_gen_transformer_arg;
            let mut c_transformer_func: gdal_sys::GDALTransformerFunc = Some(gdal_sys::GDALGenImgProjTransform);

            let mut c_approx_transformer_arg: *mut c_void = null_mut();
            if self.max_error != 0.0 {
                c_approx_transformer_arg = gdal_sys::GDALCreateApproxTransformer(
                    c_transformer_func,
                    c_gen_transformer_arg,
                    self.max_error);
                if c_approx_transformer_arg == null_mut() {
                    gdal_sys::GDALDestroyGenImgProjTransformer(c_gen_transformer_arg);
                    gdal_sys::GDALClose(c_tgt_ds);
                    return Err(last_gdal_error())
                }

                c_transformer_arg = c_approx_transformer_arg;
                c_transformer_func = Some(gdal_sys::GDALApproxTransform);
            }

            warp_options.pTransformerArg = c_transformer_arg;
            warp_options.pfnTransformer = c_transformer_func;

            let c_warp_op = gdal_sys::GDALCreateWarpOperation(c_warp_options);
            if c_warp_op == null_mut() {
                gdal_sys::GDALDestroyGenImgProjTransformer(warp_options.pTransformerArg);
                gdal_sys::GDALDestroyWarpOptions(c_warp_options);
                return Err(last_gdal_error());
            }

            let x_size = gdal_sys::GDALGetRasterXSize(c_tgt_ds);
            let y_size = gdal_sys::GDALGetRasterYSize(c_tgt_ds);

            let res = gdal_sys::GDALChunkAndWarpImage(c_warp_op, 0,0, x_size, y_size);

            gdal_sys::GDALDestroyWarpOperation(c_warp_op);
            gdal_sys::GDALDestroyGenImgProjTransformer(c_gen_transformer_arg);
            if c_approx_transformer_arg != null_mut() {
                gdal_sys::GDALDestroyApproxTransformer(c_approx_transformer_arg);
            }

            if res == gdal_sys::CPLErr::CE_None {
                gdal_sys::GDALFlushCache(c_tgt_ds);
                Ok(())
            } else {
                Err(last_gdal_error())
            }
        }
    }
}

