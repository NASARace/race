#![allow(unused)]
/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

use std::f64::consts;
use std::process::Command;
use std::path::PathBuf;

use crate::BoundingBox;

/// utility module to retrieve DEM data for given geographic bounding box as UTM grid

// no 'I' or 'O' band
static UTM_BANDS: [char;22] = ['A','B','C','D','E','F','G','H','J','K','L','M','N','P','Q','R','S','T','U','V','W','X'];
static PI: f64 = consts::PI;

#[derive(Debug)]
pub struct UtmPosition {
    zone: usize,
    band: char,
    easting: f64,
    northing: f64
}

#[derive(Debug)]
pub struct UtmBox {
    zone: usize,
    west: f64,
    south: f64,
    east: f64,
    north: f64
}

fn get_utm_zone (lat_deg: f64, lon_deg:f64) -> Result<usize,String> {
    // handle special cases (Svalbard/Norway)
    if (lat_deg > 55.0) && (lat_deg < 64.0) && (lon_deg > 2.0) && (lon_deg < 6.0) {
        return Ok(32);
    }

    if lat_deg > 71.0 {
        if (lon_deg >= 6.0) && (lon_deg < 9.0) {
            return Ok(31);
        }
        if (lon_deg >= 9.0 && lon_deg < 12.0) || (lon_deg >= 18.0 && lon_deg < 21.0) {
            return Ok(33)
        }
        if (lon_deg >= 21.0 && lon_deg < 24.0) || (lon_deg >= 30.0 && lon_deg < 33.0) {
            return Ok(35)
        }
    }

    if lon_deg >= -180.0 && lon_deg <= 180.0 {
        Ok((((lon_deg + 180.0) / 6.0) as usize % 60) + 1)

    } else if lon_deg > 180.0 && lon_deg < 360.0 {
        Ok(((lon_deg / 6.0) as usize % 60) + 1)

    } else {
        Err("illegal geographic position".to_string()) 
    }
}

// inlined convenience functions
fn to_radians (deg: f64) -> f64 { deg * PI / 180.0 }
fn sin(x: f64) -> f64 { x.sin() }
fn cos(x: f64) -> f64 { x.cos() }
fn atan(x: f64) -> f64 { x.atan() }
fn sinh(x: f64) -> f64 { x.sinh() }
fn cosh(x: f64) -> f64 { x.cosh() }
fn atanh(x: f64) -> f64 { x.atanh() }
fn sqrt(x: f64) -> f64 { x.sqrt() }

fn to_utm (lat_deg: f64, lon_deg: f64) -> Result<UtmPosition,String> {
    // let a = 6378.137;
    // let f = 0.0033528106647474805; // 1.0/298.257223563
    // let n = 0.0016792203863837047; // f / (2.0 - f)
    // let n2 = 2.8197811060466384E-6; // n * n
    // let n3 = 4.7350339184131065E-9; // n2 * n
    // let n4 = 7.951165486017604E-12; // n2 * n2
    // let A = 6367.449145823416; // (a / (1.0 + n)) * (1 + n2/4.0 + n4/64.0)
    let α1 = 8.377318188192541E-4; // n/2.0 - (2.0/3.0)*n2 + (5.0/16.0)*n3
    let α2 = 7.608496958699166E-7; // (13.0/48.0)*n2 - (3.0/5.0)*n3
    let α3 = 1.2034877875966646E-9; // (61.0/240.0)*n3
    let c = 0.08181919084262149; // (2.0*sqrt(n)) / (1.0 + n)
    // let k0 = 0.9996;
    let d = 6364.902166165087; // k0 * A
    let e0 = 500.0;

    get_utm_zone(lat_deg, lon_deg).map( |zone| {
        let band = UTM_BANDS[ ((lat_deg+80.0)/8.0) as usize];
        
        let φ = to_radians(lat_deg);
        let λ = to_radians(lon_deg);
        let λ0 = to_radians((zone-1) as f64 *6.0 - 180.0 + 3.0);
        let dλ = λ - λ0;
        let n0 = if φ < 0.0 { 10000.0 } else { 0.0 };
        
        let sin_φ = sin(φ);
        let t = sinh( atanh(sin_φ) - c * atanh( c*sin_φ));

        let ξ = atan( t/cos(dλ));
        let ξ2 = 2.0 * ξ;
        let ξ4 = 4.0 * ξ;
        let ξ6 = 6.0 * ξ;

        let η = atanh( sin(dλ) / sqrt(1.0 + t*t));
        let η2 = 2.0 * η;
        let η4 = 4.0 * η;
        let η6 = 6.0 * η;
    
        let easting = (e0 + d*(η + (α1 * cos(ξ2)*sinh(η2)) + (α2 * cos(ξ4)*sinh(η4)) + (α3 * cos(ξ6)*sinh(η6)))) * 1000.0;
        let northing = (n0 + d*(ξ + (α1 * sin(ξ2)*cosh(η2)) + (α2 * sin(ξ4)*cosh(η4)) + (α3 * sin(ξ6)*cosh(η6)))) * 1000.0;

        UtmPosition{ zone, band, easting, northing }
    })
}

fn to_utm_box (bbox: &BoundingBox) -> Result<UtmBox,String> {
    let ll = to_utm(bbox.south, bbox.west);
    let ur = to_utm(bbox.north, bbox.east);
    ll.and_then( move |utm_ll| ur.map( move |utm_ur| {
        let zone_ll = utm_ll.zone;
        let zone_ur = utm_ur.zone;
        if zone_ll == zone_ur {
            UtmBox { zone:zone_ll, west:utm_ll.easting, south:utm_ll.northing, east:utm_ur.easting, north:utm_ur.northing }
        } else {
            UtmBox { zone:zone_ll, west:utm_ll.easting, south:utm_ll.northing, east:utm_ur.easting + 500_000.0, north:utm_ur.northing }
        }
    }))
}

fn get_pathname (bbox: &BoundingBox, dir: &str) -> PathBuf {
    let mut path = PathBuf::from(dir);
    path.push( format!("{:.3}_{:.3}_{:.3}_{:.3}.tif", bbox.west, bbox.south, bbox.east, bbox.north));
    path
}

fn retrieve_dem (bbox: &BoundingBox, dem_path: &str, warp_path: &str, vrt_path: &str) -> Result<(),String> {
    to_utm_box(bbox).and_then( |bb| {
        match Command::new(warp_path)
        .arg("-t_srs")
        .arg(format!("+proj=utm +zone={} +datum=WGS84 +units=m", bb.zone))
        .arg("-te")
        .arg(format!("{:.3}", bb.west))
        .arg(format!("{:.3}", bb.south))
        .arg(format!("{:.3}", bb.east))
        .arg(format!("{:.3}", bb.north))
        .arg("-co")
        .arg("COMPRESS=DEFLATE")
        .arg("-co")
        .arg("PREDICTOR=2")
        .arg(vrt_path)
        .arg(dem_path)
        .spawn() {
            Ok(_) => Ok(()),
            Err(e) => Err(e.to_string())
        }
    })
}

pub fn get_dem_file (bbox: &BoundingBox, dir: &str, warp_path: &str, vrt_path: &str) -> Result<String,String> {
    let pb = get_pathname(bbox,dir);
    let pn_str = pb.as_path().to_str().unwrap();
    if !pb.as_path().exists() {
        retrieve_dem(bbox, pn_str, warp_path, vrt_path).map(|_| String::from(pn_str))
    } else {
        Ok(String::from(pn_str))
    }
}
