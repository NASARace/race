#![allow(unused)]

use std::fs;
use std::fs::File;
use std::path::Path;
use std::io::prelude::*;
use std::io::BufWriter;
use std::error::Error;
use geojson::{GeoJson,FeatureCollection,Feature,Value};
use structopt::StructOpt;

#[macro_use]
extern crate lazy_static;

/// command line arguments
#[derive(StructOpt,Clone,Debug)]
pub struct Arguments {
       /// if true no HRRR files will be downloaded (useful to debug schedule)
       #[structopt(long)]
       dry_run: bool,
   
       /// increase logging level (can also be set with RUST_LOG env var)
       #[structopt(short,long)]
       verbose: bool,

        #[structopt(long,default_value="perimeter")]
        perimeter_dir: String,

        #[structopt(long,default_value="ir-intense")]
        ir_intense_dir: String,

        #[structopt(long,default_value="ir-scattered")]
        ir_scattered_dir: String,

       /// path of config file to run or generate
       input_file: String,
}

lazy_static! {
    #[derive(Debug)]
    static ref ARGS: Arguments = Arguments::from_args();
}

fn check_output_dir(path_str: &str) -> bool {
    let path = Path::new(path_str);

    if path.is_dir() {
        let md = fs::metadata(&path).unwrap();
        if md.permissions().readonly() {
            eprintln!("output_dir {:?} not writable", &path);
            false
        } else {
            true
        }

    } else {
        match fs::create_dir(&path) {
            Result::Ok(_) =>  
                true,
            Err(e) => {
                eprintln!("failed to create output_dir {:?}: {:?}", &path, e);
                false
            }
        }
    }
}
/*
fn process_perimeter (feature: &Feature) {
    if check_output_dir(ARGS.perimeter_dir) {
        if let Some(dt) = get_datetime(feature) {
            let mut pn = PathBuf::new();
            path.push(ARGS.perimeter_dir);
            path.push(dt);
            println!("writing perimeter file to {}", path);

            let file = File::create(path).unwrap();
            let writer = BufWriter::new(file);
            geojson::ser::to_feature_writer(writer,feature).unwrap();
        }
    }
}
*/

fn process_geojson(gj: &GeoJson) {
    if let GeoJson::FeatureCollection(ref fc) = *gj {
        for feature in &fc.features {
            if let Some(ref properties) = feature.properties {
                if let Some(dtg) = properties.get("PolygonDateTime") {
                    if let Some(cat) = properties.get("FeatureCategory") {
                        println!("-- processing {} : {}", cat, dtg);
                    } else { println!("-- skipping feature without 'FeatureCategory' property {:?}", feature); }
                } else { println!("-- skipping feature without 'FeatureCategory' property {:?}", feature); }
            } else { println!("-- skipping feature without properties {:?}", feature); }
        }
    } else { eprintln!("geojson not a FeatureCollection"); }
}

fn main() -> Result<(),Box<dyn Error>> {
    let mut file = File::open(ARGS.input_file.as_str())?;
    let mut contents = String::new();
    file.read_to_string(&mut contents)?;

    let geojson: GeoJson = contents.parse::<GeoJson>()?;
    process_geojson(&geojson);

    Ok(())
}
