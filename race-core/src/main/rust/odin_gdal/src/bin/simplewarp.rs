
#[macro_use]
extern crate lazy_static;

use structopt::StructOpt;
use std::path::Path;
use gdal::Dataset;
use gdal::spatial_ref::SpatialRef;
use odin_gdal::{warp::SimpleWarpBuilder, get_driver_name_from_filename, to_csl_string_list};
use anyhow::{Result};


/// structopt command line arguments
#[derive(StructOpt,Debug)]
struct CliOpts {
    /// target extent <xmin ymin xmax ymax>
    #[structopt(long,allow_hyphen_values=true,number_of_values=4)]
    te: Option<Vec<f64>>,

    /// target SRS definition
    #[structopt(long)]
    t_srs: Option<String>,

    /// optional target format (default is GTiff)
    #[structopt(long)]
    tgt_format: Option<String>,

    /// optional target create options
    #[structopt(long, number_of_values=1)]
    co: Vec<String>,

    /// optional max output error threshold (default 0.0)
    #[structopt(long)]
    err_threshold: Option<f64>,

    /// input filename
    src_filename: String,

    /// output filename
    tgt_filename: String,
}

lazy_static! {
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main () -> Result<()> {
    let src_path = Path::new(ARGS.src_filename.as_str());
    let src_ds = Dataset::open(src_path)?;
    let tgt_path = Path::new(ARGS.tgt_filename.as_str());

    let tgt_srs_opt: Option<SpatialRef> = if let Some(srs_def) = &ARGS.t_srs {
        Some(SpatialRef::from_definition(srs_def.as_str())?)
        //Some(SpatialRef::from_proj4(srs_def.as_str())?)
    } else { None };

    let co_list_opt = to_csl_string_list(&ARGS.co)?;

    let tgt_format: &str = if let Some(ref fmt) = ARGS.tgt_format {
        fmt.as_str()
    } else {
        if let Some(driver_name) = get_driver_name_from_filename(ARGS.tgt_filename.as_str()) {
            driver_name
        } else {
            "GTiff" // our last fallback
        }
    };

    let mut warper = SimpleWarpBuilder::new( &src_ds, tgt_path)?;
    if let Some(v) = &ARGS.te {
        warper.set_tgt_extent(v[0],v[1],v[2],v[3]);
    }
    if let Some(ref tgt_srs) = tgt_srs_opt {
        warper.set_tgt_srs(tgt_srs);
    }
    if let Some (ref co_list) = co_list_opt {
        warper.set_create_options(co_list);
    }
    if let Some(max_error) = ARGS.err_threshold {
        warper.set_max_error(max_error);
    }

    warper.set_tgt_format(tgt_format)?;

    warper.exec()?;

    // note that Dataset has a Drop impl so we don't need to close here - we would get a segfault from GDAL if we do

    Ok(())
}