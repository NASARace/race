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
use gdal::spatial_ref::{CoordTransform, CoordTransformOptions, SpatialRef};
use gdal_sys::{CPLErrorReset, OGRErr, OSRExportToWkt, OSRNewSpatialReference, OSRSetFromUserInput, CPLErr};
use geo::{Coord, Rect};
use gdal::cpl::CslStringList;

use odin_common::fs::*;
use odin_common::macros::if_let;
use odin_common::geo::*;
use crate::errors::{Result,misc_error, last_gdal_error, OdinGdalError, gdal_error, map_gdal_error};

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

pub fn ok_true <F> (cond: bool, err: F) -> Result<()> where F: FnOnce()->String {
    if cond { Ok(()) } else { Err( OdinGdalError::MiscError(err())) }
}

pub fn ok_not_zero <F> (res: c_int, err: F) -> Result<()> where F: FnOnce()->String {
    if res != 0 { Ok(()) } else {  Err( OdinGdalError::MiscError(err())) }
}

pub fn ok_non_null <R,F> (ptr: *const R, err: F) -> Result<*const R>  where F: FnOnce()->String {
    if ptr != null() { return Ok(ptr) } else {  Err(OdinGdalError::MiscError(err())) }
}

pub fn ok_mut_non_null <R,F> (ptr: *mut R, err: F) -> Result<*mut R>  where F: FnOnce()->String {
    if ptr != null_mut() { return Ok(ptr) }  else {  Err(OdinGdalError::MiscError(err())) }
}

pub fn ok_ce_none (res: CPLErr::Type) -> Result<()> {
    if res == CPLErr::CE_None { return Ok(()) } else { Err(last_gdal_error()) }
}

pub fn gdal_badarg(details: String) -> GdalError {
    GdalError::BadArgument(details)
}

pub fn to_csl_string_list (strings: &Vec<String>) -> Result<Option<CslStringList>> {
    if ! strings.is_empty() { // don't allocate if there is nothing to convert
        let mut co_list =  CslStringList::new();
        for s in strings {
            co_list.add_string(s.as_str())?;
        }
        Ok(Some(co_list))
    } else {
        Ok(None)
    }
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

pub fn transform_point_2d (transform: &CoordTransform, x: f64, y: f64) -> Result<(f64,f64)> {
    let mut ax: [f64;1] = [x];
    let mut ay: [f64;1] = [y];
    let mut az: [f64;0] = [];

    transform.transform_coords(&mut ax, &mut ay, &mut az)?;
    Ok((ax[0],ay[0]))
}

pub fn latlon_to_utm_bounds (bbox: &BoundingBox<f64>, interior:  bool) -> (BoundingBox<f64>,u32) {
    let ll_geo = LatLon {lat_deg: bbox.south, lon_deg: bbox.west };
    let lr_geo = LatLon {lat_deg: bbox.south, lon_deg: bbox.east };
    let ul_geo = LatLon {lat_deg: bbox.north, lon_deg: bbox.west };
    let ur_geo = LatLon {lat_deg: bbox.north, lon_deg: bbox.west };

    let center_geo = LatLon {lat_deg: (ll_geo.lat_deg + ul_geo.lat_deg) / 2.0,
                             lon_deg: (ll_geo.lon_deg + lr_geo.lon_deg) / 2.0 };
    let zone = utm_zone( &center_geo).unwrap();

    let ll_utm = latlon_to_utm_zone(&ll_geo, zone).unwrap();
    let ul_utm = latlon_to_utm_zone(&ul_geo, zone).unwrap();
    let lr_utm = latlon_to_utm_zone(&lr_geo, zone).unwrap();
    let ur_utm = latlon_to_utm_zone(&ur_geo, zone).unwrap();

    let (west, east) = if interior {
        ( ll_utm.easting.max( ul_utm.easting), lr_utm.easting.min( ur_utm.easting) )
    } else {
        ( ll_utm.easting.min( ul_utm.easting), lr_utm.easting.max( ur_utm.easting) )
    };
    (BoundingBox {west, south: ll_utm.northing, east, north: ul_utm.northing}, zone)
}

pub fn transform_latlon_to_utm_bounds (west_deg: f64, south_deg: f64, east_deg: f64, north_deg: f64,
                                       interior:  bool, utm_zone: Option<u32>, is_south: bool) -> Result<(f64,f64,f64,f64,u32)> {
    let s_srs = srs_epsg_4326(); // axis order is lat,lon, uom: degrees

    let (t_srs,zone) = if let Some(zone) = utm_zone {
        let zone_base = if is_south { 32700 } else { 32600 };
        (srs_epsg( zone_base + zone)?, zone)
    } else {
        let (lon_center,lat_center) = bounds_center(west_deg,south_deg,east_deg,north_deg);
        srs_utm_from_lon_lat(lon_center, lat_center, utm_zone)?
    };

    //let transform = CoordTransform::new(&s_srs, &t_srs)?;
    let mut ct_options = CoordTransformOptions::new()?;
    ct_options.desired_accuracy( 0.0);
    ct_options.set_ballpark_allowed(false);
    let transform = CoordTransform::new_with_options(&s_srs, &t_srs, &ct_options)?;

    let (x_ll,y_ll) = transform_point_2d( &transform, south_deg, west_deg)?;
    let (x_lr,y_lr) = transform_point_2d( &transform, south_deg, east_deg)?;
    let (x_ul,y_ul) = transform_point_2d( &transform, north_deg, west_deg)?;
    let (x_ur,y_ur) = transform_point_2d( &transform, north_deg, east_deg)?;

    if interior {
        Ok( (x_ll.max(x_ul),  y_ll.max(y_lr), x_lr.min(x_ur), y_ul.min(y_ur), zone) )
    } else {
        Ok( (x_ll.min(x_ul),  y_ll.min(y_lr), x_lr.max(x_ur), y_ul.max(y_ur), zone) )
    }
}

pub fn transform_utm_to_latlon_bounds (west_m: f64, south_m: f64, east_m: f64, north_m: f64, interior: bool, utm_zone: u32, is_south: bool) -> Result<(f64,f64,f64,f64)> {
    let zone_base = if is_south { 32700 } else { 32600 };
    let s_srs = srs_epsg( zone_base + utm_zone)?;
    let t_srs = srs_epsg_4326();

    let mut ct_options = CoordTransformOptions::new()?;
    ct_options.desired_accuracy( 0.0);
    ct_options.set_ballpark_allowed(false);
    let transform = CoordTransform::new_with_options(&s_srs, &t_srs, &ct_options)?;

    let (y_ll,x_ll) = transform_point_2d( &transform, west_m, south_m)?;
    let (y_lr,x_lr) = transform_point_2d( &transform, east_m, south_m)?;
    let (y_ul,x_ul) = transform_point_2d( &transform, west_m, north_m)?;
    let (y_ur,x_ur) = transform_point_2d( &transform, east_m, north_m)?;

    if interior {
        Ok( (x_ll.max(x_ul),  y_ll.max(y_lr), x_lr.min(x_ur), y_ul.min(y_ur)) )
    } else {
        Ok( (x_ll.min(x_ul),  y_ll.min(y_lr), x_lr.max(x_ur), y_ul.max(y_ur)) )
    }
}

// watch out - if source or target are geographic we might have to swap axis order
// (we don't want to change axis_mapping_strategy in the provided SpatialRefs though)
// TODO - round trips between epsg:4326 and UTM produce differing results also in lat/northing, find out why
pub fn transform_bounds_2d (s_srs: &SpatialRef, t_srs: &SpatialRef,
                            x_min: f64, y_min: f64,
                            x_max: f64, y_max: f64,
                            opt_densify_pts: Option<i32>) -> Result<(f64,f64,f64,f64)> {

    let s_is_geo = s_srs.is_geographic();
    let t_is_geo = t_srs.is_geographic();

    let mut bounds: [f64;4] = if s_is_geo && !t_is_geo { [y_min,x_min,y_max,x_max] } else { [x_min,y_min,x_max,y_max] };
    let densify_pts: i32 = if let Some(dp) = opt_densify_pts { dp } else { 21 }; // default recommended by GDAL OCTTransformBounds doc

    let mut ct_options = CoordTransformOptions::new()?;
    ct_options.desired_accuracy( 0.0);
    ct_options.set_ballpark_allowed(false);

    //CoordTransform::new(s_srs,t_srs)
    CoordTransform::new_with_options(s_srs,t_srs, &ct_options)
        .and_then( |transform| transform.transform_bounds(&mut bounds, densify_pts))
        .map_err(|e| {
            gdal_error(e)})
        .and_then( |a| {
            let ta = if t_is_geo && !s_is_geo { (a[1], a[0], a[3], a[2]) } else { (a[0], a[1], a[2], a[3]) };
            Ok(ta)
        })
}

//--- well known SpatialRefs

pub fn srs_lon_lat () -> SpatialRef { SpatialRef::from_epsg(4326).unwrap() }
pub fn srs_epsg_4326 () -> SpatialRef { SpatialRef::from_epsg(4326).unwrap() }

pub fn srs_utm_10_n() -> SpatialRef { SpatialRef::from_epsg(32610).unwrap() } // US Pacific coast (north west  CA)
pub fn srs_utm_11_n() -> SpatialRef { SpatialRef::from_epsg(32611).unwrap() } // south/east CA, east WA, east OR, west ID, west MT, west AZ, NV
pub fn srs_utm_12_n() -> SpatialRef { SpatialRef::from_epsg(32612).unwrap() } // UT, AZ, east ID, central MT, west WY, west CO, west NM
pub fn srs_utm_13_n() -> SpatialRef { SpatialRef::from_epsg(32613).unwrap() } // east MT, east WY, CO, NM, west ND, west SD

pub fn srs_epsg (utm_zone: u32) -> Result<SpatialRef> {
    Ok(SpatialRef::from_epsg(utm_zone)?)
}

pub fn srs_utm_n (zone: u32) -> Result<SpatialRef> {
    Ok(SpatialRef::from_epsg(32600 + zone)?)
}

pub fn srs_utm_s (zone: u32) -> Result<SpatialRef> {
    Ok(SpatialRef::from_epsg(32700 + zone)?)
}

pub fn srs_utm_from_lon_lat (lon_deg: f64, lat_deg: f64, opt_zone: Option<u32>) -> Result<(SpatialRef,u32)> {
    let utm_zone = if let Some(zone) = opt_zone {
        if zone <= 60 { zone } else {
            return Err(misc_error(format!("invalide UTM zone: {}", zone)));
        }
    } else {
        if let Ok(zone) = utm_zone(lon_deg,lat_deg) { zone } else {
            return Err(misc_error(format!("invalid geographic lon,lat: {},{}", lon_deg, lat_deg)))
        }
    };

    let epsg_base = if lat_deg < 0.0 { 32700 } else { 32600 };
    Ok(SpatialRef::from_epsg(epsg_base + utm_zone).map( |srs| (srs,utm_zone))?)
}
