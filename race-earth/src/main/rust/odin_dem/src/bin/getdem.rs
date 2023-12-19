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
#![allow(unused)]

use odin_dem::*;
use lazy_static::lazy_static;
use structopt::StructOpt;
use odin_common:: {
    geo::BoundingBox,
    fs,
};

#[derive(StructOpt)]
struct CliOpts {
    /// west boundary in degrees
    #[structopt(long, short, allow_hyphen_values = true)]
    west: f64,

    /// south boundary in degrees
    #[structopt(long, short, allow_hyphen_values = true)]
    south: f64,

    /// east boundary in degrees
    #[structopt(long, short, allow_hyphen_values = true)]
    east: f64,

    /// north boundary in degrees
    #[structopt(long, short, allow_hyphen_values = true)]
    north: f64,

    /// the image type to create
    #[structopt(short,long,default_value="tif")]
    img_type: String,

    /// where to store created dem files
    #[structopt(short,long,default_value="cache")]
    cache_dir: String,

    /// the GDAL *.vrt file to create the DEM from
    vrt_file: String
}

lazy_static! {
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main() {
    let bbox = BoundingBox::from_wsen::<f64>(&[ARGS.west, ARGS.south, ARGS.east, ARGS.north]);
    if let Some(img_type) = DemImgType::for_ext(ARGS.img_type.as_str()) {
        if fs::existing_non_empty_file_from_path(&ARGS.vrt_file).is_ok() {
            if fs::ensure_writable_dir(&ARGS.cache_dir).is_ok() {
                let srs = DemSRS::UTM { epsg: 32610 };

                match get_dem(&bbox,srs,img_type, ARGS.cache_dir.as_str(),ARGS.vrt_file.as_str()) {
                    Ok((file_path, file)) => println!("DEM file at {}", file_path),
                    Err(e) => eprintln!("failed to create DEM file, error: {}", e)
                }

            } else { eprintln!("invalid cache dir {}", ARGS.cache_dir) }
        } else { eprintln!("VRT file not found {}", ARGS.vrt_file) }
    } else { eprintln!("unknown target image type {}", ARGS.img_type) }

}