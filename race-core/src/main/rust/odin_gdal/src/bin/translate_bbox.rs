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
use lazy_static::lazy_static;
use structopt::StructOpt;
use gdal::spatial_ref::SpatialRef;
use odin_gdal::{transform_bounds_2d,errors::Result};

#[derive(StructOpt)]
#[structopt(global_setting = structopt::clap::AppSettings::AllowNegativeNumbers)]
struct CliOpts {
    /// number of points to use to densify bounding polygon
    #[structopt(long,allow_hyphen_values=true)]
    densify: Option<i32>,

    /// source SRS (used for min/max coordinates)
    #[structopt(short,long)]
    s_srs: String,

    /// target SRS (to convert to)
    #[structopt(short,long)]
    t_srs: String,

    x_min: f64,
    y_min: f64,
    x_max: f64,
    y_max: f64,
}

lazy_static! {
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main() -> Result<()> {
    let src_srs = SpatialRef::from_definition( &ARGS.s_srs.as_str())?;
    let tgt_srs = SpatialRef::from_definition( &ARGS.t_srs.as_str())?;

    println!("@@ {} {} {} {}", ARGS.x_min, ARGS.y_min, ARGS.x_max, ARGS.y_max);
    let (x_min,y_min,x_max,y_max) = transform_bounds_2d( &src_srs, &tgt_srs,
                                                         ARGS.x_min, ARGS.y_min, ARGS.x_max, ARGS.y_max,
                                                         ARGS.densify)?;

    println!(" from: '{}'", src_srs.to_proj4()?);
    println!(" to:   '{}'", tgt_srs.to_proj4()?);

    println!("  x_min:  {:15.4} -> {:10.4}", ARGS.x_min, x_min);
    println!("  y_min:  {:15.4} -> {:10.4}", ARGS.y_min, y_min);
    println!("  x_max:  {:15.4} -> {:10.4}", ARGS.x_max, x_max);
    println!("  y_max:  {:15.4} -> {:10.4}", ARGS.y_max, y_max);

    Ok(())
}