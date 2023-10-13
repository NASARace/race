
#[macro_use]
extern crate lazy_static;

use structopt::StructOpt;
use std::path::Path;
use gdal::Dataset;
use gdal::spatial_ref::SpatialRef;
use gdal::cpl::CslStringList;
use odin_gdal::warp::SimpleWarpBuilder;
use odin_gdal::get_driver_name_from_filename;
use anyhow::{Result};


/// structopt command line arguments
#[derive(StructOpt)]
struct CliOpts {
    /// target extent <xmin ymin xmax ymax>
    #[structopt(long,allow_hyphen_values=true,number_of_values=4)]
    te: Option<Vec<f64>>,

    /// target SRS definition
    #[structopt(long)]
    t_srs: Option<String>,

    #[structopt(long)]
    tgt_format: Option<String>,

    /// target create options
    #[structopt(long)]
    co: Vec<String>,

    /// input filename
    src_filename: String,

    /// output filename
    tgt_filename: String,
}

lazy_static! {
    #[derive(Debug)]
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main () -> Result<()> {
    let src_path = Path::new(ARGS.src_filename.as_str());
    let src_ds = Dataset::open(src_path)?;
    let tgt_path = Path::new(ARGS.tgt_filename.as_str());

    let tgt_srs_opt: Option<SpatialRef> = if let Some(srs_def) = &ARGS.t_srs {
        //Some(SpatialRef::from_definition(srs_def.as_str())?)
        Some(SpatialRef::from_proj4(srs_def.as_str())?)
    } else { None };

    let co_list_opt: Option<CslStringList> = if ! ARGS.co.is_empty() {
        let mut co_list =  CslStringList::new();
        for s in &ARGS.co {
            co_list.add_string(s.as_str())?;
        }
        Some(co_list)
    } else { None };

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

    warper.set_tgt_format(tgt_format)?;

    warper.exec()?;

    // note that Dataset has a Drop impl so we don't need to close here - we would get a segfault from GDAL if we do

    Ok(())
}