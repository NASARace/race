#![allow(unused)]

pub mod errors;

use std::error::Error;
use std::fs::File;
use std::path::{Path, PathBuf};
use axum::http::{header, HeaderMap, HeaderValue};
use axum::http::header::CONTENT_TYPE;
use axum::response::IntoResponse;
use axum::body::Body;
use tokio::io;
use gdal::Dataset;
use gdal::spatial_ref::SpatialRef;
use gdal::cpl::CslStringList;
use lazy_static::lazy_static;
use odin_common::fs;
use odin_gdal::warp::SimpleWarpBuilder;
use odin_common::geo::BoundingBox;

use crate::errors::OdinDemError;

type Result<T> = std::result::Result<T, OdinDemError>;

//--- supported image and target SRS types

pub enum DemImgType {
    PNG,
    TIF,
}

impl DemImgType {
    pub fn for_ext (file_ext: &str) -> Option<DemImgType> {
        match file_ext {
            "tif" => Some(DemImgType::TIF),
            "png" => Some(DemImgType::PNG),
            _ => None
        }
    }

    pub fn file_extension(&self) -> &'static str {
        match *self {
            DemImgType::PNG => "png",
            DemImgType::TIF => "tif",
        }
    }

    pub fn gdal_driver_name(&self) -> &'static str {
        match *self {
            DemImgType::PNG => "PNG",
            DemImgType::TIF => "GTiff",
        }
    }

    pub fn content_type(&self) -> &'static str {
        match *self {
            DemImgType::PNG => "image/png",
            DemImgType::TIF => "image/tiff",
        }
    }
}

pub enum DemSRS {
    GEO,
    UTM { epsg: u32 },
}

impl DemSRS {
    pub fn from_epsg (epsg: u32) -> Result<DemSRS> {
        if epsg == 4326 {
            Ok(DemSRS::GEO)
        } else if (epsg >= 32601 && epsg <= 32660) || (epsg >= 32701 && epsg <= 32760) {
            Ok(DemSRS::UTM{epsg})
        } else {
            Err(OdinDemError::UnsupportedTargetSRS(format!("{}", epsg)))
        }
    }

    pub fn epsg(&self) -> u32 {
        match *self {
            DemSRS::GEO => 4326,
            DemSRS::UTM{epsg} => epsg,
        }
    }

    pub fn bbox_precision(&self) -> usize {
        match *self {
            DemSRS::GEO => 4,
            DemSRS::UTM{..} => 0,
        }
    }
}

//--- output image creation

fn create_opts() -> CslStringList {
    let mut co_list = CslStringList::new();
    co_list.add_string("COMPRESS=DEFLATE");
    co_list.add_string("PREDICTOR=2");
    co_list
}

fn get_filename (bbox: &BoundingBox<f64>, precision: usize, file_ext: &str) -> String {
    format!("dem_{:.precision$},{:.precision$},{:.precision$},{:.precision$}.{}",
            bbox.west, bbox.south, bbox.east, bbox.north, file_ext)
}

fn create_file (bbox: &BoundingBox<f64>, srs: DemSRS, img_type: DemImgType, output_path: &Path, vrt_path: &Path) -> Result<File> {
    let src_ds =  Dataset::open(vrt_path)?;
    let tgt_srs = SpatialRef::from_epsg(srs.epsg())?;
    let co_list = create_opts();

    SimpleWarpBuilder::new( &src_ds, output_path)?
        .set_tgt_srs(&tgt_srs)
        .set_tgt_extent_from_bbox(bbox)
        .set_tgt_format(img_type.gdal_driver_name())?
        .set_create_options(&co_list)
        .exec()?;

    Ok(fs::existing_non_empty_file_from_path(output_path)?)
}

//--- HTTP response creation

async fn create_response (file: File, img_type: DemImgType) -> impl IntoResponse {
    let f = tokio::fs::File::from_std(file);
    let stream = tokio_util::io::ReaderStream::new(f);
    let body = Body::from_stream(stream);

    let mut headers = HeaderMap::new();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static(img_type.content_type()));

    (headers,body)
}

//--- main lib entry

/// for a given bounding box 'bbox' look for a matching file in 'cache_dir'.
/// If none found yet create a file with the given 'img_type' from the virtual GDAL tileset specified by 'vrt_file'
pub fn get_dem (bbox: &BoundingBox<f64>, srs: DemSRS, img_type: DemImgType, cache_dir: &str, vrt_file: &str) -> Result<(String,File)> {
    fs::ensure_dir(cache_dir)?;

    let fname = get_filename(bbox, srs.bbox_precision(), img_type.file_extension());
    let file_path: PathBuf = [cache_dir, fname.as_str()].iter().collect();

    let vrt_path = Path::new(vrt_file);
    vrt_path.try_exists()?;

    let res = if !file_path.exists() {
        create_file(bbox,srs,img_type,&file_path, &vrt_path)
    } else {
        Ok(File::open(&file_path)?)
    };

    res.map( |f| (fs::path_to_lossy_string(&file_path),f) )
}
