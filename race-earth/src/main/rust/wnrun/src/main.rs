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

/// server to run WindNinja 
/// test manually with 
///   curl -X POST -H "Content-Type: application/json" http://localhost:8080/wnrun --data-binary "@race-earth/src/main/rust/wnrun/resources/test/wn_args.json"

mod fetchdem;

 #[macro_use]
 extern crate lazy_static;
 
 use std::{
     //process::ExitCode,
     //io::Result,
     process::Command,
 };
 use warp::{
     http,
     Filter,
 };
 use http::status::StatusCode;
 use structopt::StructOpt;

use serde::{Deserialize};
use chrono::{
    DateTime,
    Datelike,
    Timelike,
};

/// command line argument structure
 #[derive(Clone,Debug,StructOpt)]
 struct Opt {
     #[structopt(long,default_value="8080")]
     port: u16,
 
     #[structopt(short,long)]
     verbose: bool,
 
     #[structopt(long,default_value="wnrun")]
     end_point: String, // URL name

     #[structopt(long,default_value="dem")]
     dem_path: String, // path to directory where DEM files are cached

     #[structopt(long,default_value="gdalwarp")]
     warp_path: String, // path to gdalwarp executable

     #[structopt(long,default_value="srtm/CA.vrt")]
     vrt_path: String, // path to *.vrt file specifying the virtual tile set for the DEM data

     #[structopt(long,default_value="WindNinja_cli")]
     wn_path: String, // path to WindNinja executable

     #[structopt(short,long,default_value="output")]
     output_path: String, // path to directory where WindNinja output is stored

     #[structopt(long,default_value="hrrr")]
     wx_path: String, // path to downloaded weather data

     #[structopt(long,default_value="https://nomads.ncep.noaa.gov/cgi-bin/filter_hrrr_2d.pl")]
     filtered_hrrr_url: String, // url to retrieve filtered HRRR forecasts from NOAA
 }
 
 lazy_static! {
     static ref OPT: Opt = Opt::from_args();
 }

 #[derive(Deserialize,Debug)]
pub struct BoundingBox {
    west: f64,
    south: f64,
    east: f64,
    north: f64
}

/// data model for WindNinja arguments
#[derive(Deserialize,Debug)]
struct WnArgs {
    bbox: BoundingBox,
    mesh_resolution: u64,
    wind_height: u64,
    datetime: DateTime<chrono::Utc>,
}

fn run_windninja (args: &WnArgs) -> Result<String,String> {
    fetchdem::get_dem_file( &args.bbox, OPT.dem_path.as_str(), OPT.warp_path.as_str(), OPT.vrt_path.as_str()).and_then( |dem_file| {
        match Command::new(OPT.wn_path.as_str())
        .arg("--mesh_resolution")
        .arg(format!("{}", args.mesh_resolution))
        .arg("--units_mesh_resolution")
        .arg("m")
        .arg("--output_wind_height")
        .arg(format!("{}", args.wind_height))
        .arg("--units_output_wind_height")
        .arg("m")
        .arg("--elevation_file")
        .arg(&dem_file)
        .arg("--initialization_method")
        .arg("wxModelInitialization")
        .arg("--forecast_filename")
        .arg( "FIXME")  // wx model file name 
        .arg("--forecast_time")
        .arg( "FIXME") // datetime string (UTC)
        .arg("--start_year")
        .arg(args.datetime.year().to_string())
        .arg("--start_month")
        .arg(args.datetime.month().to_string())
        .arg("--start_day")
        .arg(args.datetime.day().to_string())
        .arg("--start_hour")
        .arg(args.datetime.hour().to_string())
        .arg("--stop_year")
        .arg(args.datetime.year().to_string())
        .arg("--stop_month")
        .arg(args.datetime.month().to_string())
        .arg("--stop_day")
        .arg(args.datetime.day().to_string())
        .arg("--stop_hour")
        .arg(args.datetime.hour().to_string())
        .arg( "--write_goog_output")
        .arg( "false")
        .arg( "--write_shapefile_output")
        .arg( "false")
        .arg( "--write_pdf_output")
        .arg( "false")
        .arg( "--write_farsite_atm")
        .arg( "false")
        .arg( "--write_wx_model_goog_output")
        .arg( "false")
        .arg( "--write_wx_model_shapefile_output")
        .arg( "false")
        .arg( "--write_wx_model_ascii_output")
        .arg( "false")
        .arg( "--write_wx_station_kml")
        .arg( "false")
        .arg( "--write_huvw_output")
        .arg( "true")
        .arg("--diurnal_winds")
        .arg( "true")
        .arg( "--output_path")
        .arg( &OPT.output_path)
        .spawn() {
            Ok(_) => Ok(dem_file),
            Err(e) => Err(e.to_string()) 
        }
    })
}

 #[tokio::main]
async fn main() {
    let route = warp::path(OPT.end_point.as_str())
    .and( warp::post())
    .and( warp::body::content_length_limit(1024 * 32))
    .and( warp::body::json())
    .map( |args: WnArgs| {
        println!("got {:?}", args);

        match run_windninja(&args) {
            Ok(dem_path) => {
                // TODO - needs to create body from dem_path file
                print!("success - created {}", dem_path);
                http::Response::builder().body(dem_path)
            }
            Err(e) => {
                print!("error while running WindNinja: {}", e);
                http::Response::builder().status(StatusCode::INTERNAL_SERVER_ERROR).body("failed to run WindNinja".to_string())
            }
        }
    });


    let log = warp::log::custom(|info| {
        println!("{} request from {} for \"{}\" -> {} after {} msec", 
            info.method(), info.remote_addr().unwrap(), info.path(), info.status(), info.elapsed().as_millis() )
    });

    println!("response server running on http://localhost:{}/{}", OPT.port, OPT.end_point);
    println!("(terminate with ctrl-C)");

    if OPT.verbose {
        warp::serve( route.with(log) ).run(([127, 0, 0, 1], OPT.port)).await
    } else {
        warp::serve( route ).run(([127, 0, 0, 1], OPT.port)).await
    }
}