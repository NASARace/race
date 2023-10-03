#![allow(unused)]

pub mod errors;
pub mod warp;

use gdal;
use gdal_sys;

#[macro_use]
extern crate lazy_static;

use std::collections::HashMap;
use std::ptr::{null, null_mut};
use std::ffi::{CString,CStr};
use libc::{c_void,c_char,c_uint, c_int};
use gdal::{Driver, DriverManager, Metadata, errors::GdalError, GeoTransform};
use gdal::spatial_ref::{CoordTransform, SpatialRef};
use gdal_sys::{CPLErrorReset, OGRErr, OSRExportToWkt, OSRNewSpatialReference, OSRSetFromUserInput, CPLErr};
use geo::{Rect};
use anyhow::{anyhow,Result,Error};

use odin_common::fs::*;
use odin_common::macros::if_let;

lazy_static! {
    // note that we can't automatically populate this by iterating over DriverManager since some
    // drivers use the same file extension
    static ref EXT_MAP: HashMap<&'static str, &'static str> = HashMap::from( [ // file extension -> driver short name
        //-- well known raster drivers
        ("tif", "GTiff"),
        ("png", "PNG"),
        ("nc", "netCDF"),
        ("grib2", "GRIB"),

        //--- vector drivers
        ("json", "GeoJSON"),
        ("geojson", "GeoJSON"),
        ("ndjson", "GeoJSONSeq"),
        ("csv", "CSV"),
        ("gpx", "GPX"),
        ("kml", "KML"),
        ("svg", "SVG"),
        ("pdf", "PDF"),
        ("shp", "ESRI Shapefile"),

        //... and many more to follow (see http://gdal.org/drivers
    ]);
}

pub fn initialize_gdal() -> bool {
    EXT_MAP.len() > 0
}

pub fn get_driver_name_from_filename (filename: &str) -> Option<&'static str> {
    get_filename_extension(filename).and_then( |ext| EXT_MAP.get( ext.to_lowercase().as_str()).map(|v| *v))
}

pub fn get_driver_from_filename (filename: &str) -> Option<gdal::Driver> {
    get_filename_extension(filename)
        .and_then( |ext| EXT_MAP.get( ext.to_lowercase().as_str()))
        .and_then( |n| DriverManager::get_driver_by_name(n).ok())
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

pub fn gdal_badarg(details: String) -> GdalError {
    GdalError::BadArgument(details)
}

#[macro_export]
macro_rules! gdal_badarg {
    ($msg: literal) => {
        gdal_badarg(format!($msg))
    };

    ($fmt_str: literal , $($arg:expr),+) => {
        gdal_badarg(format!($fmt_str, $($arg),+))
    }
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

//--- SpatialRef based coordinate transformations

pub fn bounds_center (x_min: f64, y_min: f64, x_max: f64, y_max: f64) -> (f64,f64) {
    let x_center = (x_min + x_max) / 2.0;
    let y_center = (y_min + y_max) / 2.0;
    (x_center, y_center)
}

pub fn transform_point_2d (s_srs: &SpatialRef, t_srs: &SpatialRef, x: f64, y: f64) -> Result<(f64,f64),GdalError> {
    let mut ax: [f64;1] = [x];
    let mut ay: [f64;1] = [y];
    let mut az: [f64;0] = [];

    CoordTransform::new(s_srs,t_srs)
        .and_then( |transform| transform.transform_coords(&mut ax, &mut ay, &mut az))
        .and_then( |_| Ok((ax[0],ay[0])))
}

pub fn transform_latlon_to_utm_bounds (west_deg: f64, south_deg: f64, east_deg: f64, north_deg: f64,
                                       interior:  bool, utm_zone: Option<u32>) -> Result<(f64,f64,f64,f64,u32),GdalError> {
    let s_srs = srs_epsg_4326(); // axis order is lat,lon, uom: degrees
    let (lon_center,lat_center) = bounds_center(west_deg,south_deg,east_deg,north_deg);
    let mut bounds: [f64;4] = [south_deg,west_deg,north_deg,east_deg];

    if_let! {
        Ok((t_srs,zone)) = srs_utm_from_lon_lat(lon_center, lat_center, utm_zone), r => Err(r.unwrap_err()); // UTM axis order is easting, northing, uom: meters
        Ok(transform) = CoordTransform::new(&s_srs, &t_srs),  r => Err(r.unwrap_err());
        Ok((x_ll,y_ll)) = transform_point_2d(&s_srs,&t_srs, south_deg, west_deg), r => Err(r.unwrap_err());
        Ok((x_lr,y_lr)) = transform_point_2d(&s_srs,&t_srs, south_deg, east_deg), r => Err(r.unwrap_err());
        Ok((x_ul,y_ul)) = transform_point_2d(&s_srs,&t_srs, north_deg, west_deg), r => Err(r.unwrap_err());
        Ok((x_ur,y_ur)) = transform_point_2d(&s_srs,&t_srs, north_deg, east_deg), r => Err(r.unwrap_err()) => {
            if interior {
                Ok( (x_ll.max(x_ul),  y_ll.max(y_lr), x_lr.min(x_ur), y_ul.min(y_ur), zone) )
            } else {
                Ok( (x_ll.min(x_ul),  y_ll.min(y_lr), x_lr.max(x_ur), y_ul.max(y_ur), zone) )
            }
        }
    }
}

// watch out - general case axis order needs to be resolved by caller
pub fn transform_bounds_2d (s_srs: &SpatialRef, t_srs: &SpatialRef,
                            x_min: f64, y_min: f64,
                            x_max: f64, y_max: f64,
                            opt_densify_pts: Option<i32>) -> Result<(f64,f64,f64,f64),GdalError> {

    //let mut bounds: [f64;4] = [x_min,y_min,x_max,y_max];
    let mut bounds: [f64;4] = [y_min,x_min,y_max,x_max];

    let densify_pts: i32 = if let Some(dp) = opt_densify_pts { dp } else { 21 }; // default recommendet by GDAL OCTTransformBounds doc

    CoordTransform::new(s_srs,t_srs)
        .and_then( |transform| transform.transform_bounds(&mut bounds, densify_pts))
        //.and_then( |a| Ok((a[0],a[1],a[2],a[3])))
        .and_then( |a| Ok((a[1],a[0],a[3],a[2])))
}

//--- well known SpatialRefs

pub fn srs_lon_lat () -> SpatialRef { SpatialRef::from_epsg(4326).unwrap() }
pub fn srs_epsg_4326 () -> SpatialRef { SpatialRef::from_epsg(4326).unwrap() }

pub fn srs_utm_10_n() -> SpatialRef { SpatialRef::from_epsg(32610).unwrap() } // US Pacific coast (north west  CA)
pub fn srs_utm_11_n() -> SpatialRef { SpatialRef::from_epsg(32611).unwrap() } // south/east CA, east WA, east OR, west ID, west MT, west AZ, NV
pub fn srs_utm_12_n() -> SpatialRef { SpatialRef::from_epsg(32612).unwrap() } // UT, AZ, east ID, central MT, west WY, west CO, west NM
pub fn srs_utm_13_n() -> SpatialRef { SpatialRef::from_epsg(32613).unwrap() } // east MT, east WY, CO, NM, west ND, west SD

pub fn srs_epsg (utm_zone: u32) -> Result<SpatialRef,GdalError> { SpatialRef::from_epsg(utm_zone) }

pub fn utm_zone (lon_deg: f64, lat_deg: f64) -> Result<u32,GdalError> {
    let lon = lon_deg; // should we normalize here
    let lat = lat_deg;

    // handle special cases (Svalbard/Norway)
    if lat > 55.0 && lat < 64.0 && lon > 2.0 && lon < 6.0 {
        return Ok(32)
    }

    if lat > 71.0 {
        if lon >= 6.0 && lon < 9.0 {
            return Ok(31)
        }
        if (lon >= 9.0 && lon < 12.0) || (lon >= 18.0 && lon < 21.0) {
            return Ok(33)
        }
        if (lon >= 21.0 && lon < 24.0) || (lon >= 30.0 && lon < 33.0) {
            return Ok(35)
        }
    }

    if lon >= -180.0 && lon <= 180.0 {
        Ok(((((lon + 180.0) / 6.0) as u32) % 60) + 1)
    } else if lon > 180.0 && lon < 360.0 {
        Ok((((lon / 6.0) as u32) % 60) + 1)

    } else {
        Err(GdalError::BadArgument (format!("invalid lon/lat degrees: {},{}", lon_deg, lat_deg)))
        //Err(anyhow!("invalid geographic position {},{}", lon_deg, lat_deg))
    }
}

pub fn srs_utm_n (zone: u32) -> Result<SpatialRef,GdalError> { SpatialRef::from_epsg(32600 + zone) }

pub fn srs_utm_s (zone: u32) -> Result<SpatialRef,GdalError> { SpatialRef::from_epsg(32700 + zone) }

pub fn srs_utm_from_lon_lat (lon_deg: f64, lat_deg: f64, opt_zone: Option<u32>) -> Result<(SpatialRef,u32),GdalError> {
    let utm_zone = if let Some(zone) = opt_zone {
        if zone <= 60 { zone } else {
            return Err(gdal_badarg(format!("invalide UTM zone: {}", zone)));
        }
    } else {
        if let Ok(zone) = utm_zone(lon_deg,lat_deg) { zone } else {
            return Err(gdal_badarg(format!("invalid geographic lon,lat: {},{}", lon_deg, lat_deg)))
        }
    };

    let epsg_base = if lat_deg < 0.0 { 32700 } else { 32600 };
    SpatialRef::from_epsg(epsg_base + utm_zone).map( |srs| (srs,utm_zone))
}