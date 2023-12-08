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

    /// id_field in output 
    #[structopt(long)]
    id: Option<u32>,

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

    // parsing optional arguments

    let attr_min_name: Option<&str> = if let Some(amin) = &ARGS.amin {
        Some(amin.as_str())
    } else { None };

    let attr_max_name: Option<&str> = if let Some(amax) = &ARGS.amax {
        Some(amax.as_str())
    } else { None };

    let attr_name: Option<&str> = if let Some(attr) = &ARGS.attr {
        Some(attr.as_str())
    } else { None };

    let tgt_layer: Option<&str> = if let Some(tgt_l) = &ARGS.tgt_layer {
        Some(tgt_l.as_str())
    } else { None };

    let id_field: Option<u32> = if let Some(id) = &ARGS.id {
        Some(*id)
    } else { None };




    let mut contourer = ContourBuilder::new( &src_ds, tgt_path)?;
    contourer.set_band(band);
    contourer.set_interval(interval);

    // setting optional arguments

    if attr_name.is_some() {
        contourer.set_attr_name(attr_name.unwrap());
    }
    if attr_max_name.is_some() {    
        contourer.set_attr_max_name(attr_max_name.unwrap());
    }
    if attr_min_name.is_some() {
        contourer.set_attr_min_name(attr_min_name.unwrap());
    }
    if tgt_layer.is_some() {
        contourer.set_tgt_layer_name(tgt_layer.unwrap());
    }
    if id_field.is_some() {
        contourer.set_field_id(id_field.unwrap());
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