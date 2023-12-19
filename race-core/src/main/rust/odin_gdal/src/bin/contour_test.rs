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
#[macro_use]
extern crate lazy_static;

use structopt::StructOpt;
use std::path::Path;
use gdal::Dataset;
use odin_gdal::contour::ContourBuilder;
use anyhow::Result;

/// structopt command line arguments
#[derive(StructOpt,Debug)]
struct CliOpts {
    /// tgt_layer_name in output
    #[structopt(long)]
    tgt_layer: Option<String>,

    /// set to 3d elevation
    #[structopt(long)]
    three_d: bool,

    /// attr name
    #[structopt(long, short)]
    attr: Option<String>,

    /// attr_max_name
    #[structopt(long)]
    amax: Option<String>,

    /// attr_min_name
    #[structopt(long)]
    amin: Option<String>,

    /// polygonize
    #[structopt(long, short)]
    polygon: bool,

    /// band number
    #[structopt(long, short)]
    band: i32,

    /// interval 
    #[structopt(long, short)]
    interval: i32,

    /// input filename
    src_filename: String,

    /// output filename
    tgt_filename: String,

}

lazy_static! {
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main () -> Result<()> {

    // parsing mandatory arguments

    let src_path = Path::new(ARGS.src_filename.as_str());
    let tgt_path = Path::new(ARGS.tgt_filename.as_str()); 
    let src_ds = Dataset::open(src_path)?;
    let interval = ARGS.interval;
    let band = ARGS.band;

    // setting mandatory arguments

    let mut contourer = ContourBuilder::new( &src_ds, tgt_path)?;
    contourer.set_band(band);
    contourer.set_interval(interval);

    // parsing and setting optional arguments

    if let Some(amin) = &ARGS.amin {
        contourer.set_attr_min_name(amin.as_str())?;
    }

    if let Some(amax) = &ARGS.amax {
        contourer.set_attr_max_name(amax.as_str())?;
    } 

    if let Some(attr) = &ARGS.attr {
        contourer.set_attr_name(attr.as_str())?;
    }

    if let Some(tgt_l) = &ARGS.tgt_layer {
        contourer.set_tgt_layer_name(tgt_l.as_str())?;
    }

    if ARGS.polygon {
        contourer.set_poly();
    }
    if ARGS.three_d {
        contourer.set_3_d();
    }

    // execute
    contourer.exec()?;

    Ok(())
}