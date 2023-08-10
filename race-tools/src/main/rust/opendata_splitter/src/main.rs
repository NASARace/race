#![allow(unused)]

use std::{fs,io};
use std::fs::File;
use std::path::{Path,PathBuf};
use std::io::prelude::*;
use std::io::BufWriter;
use std::error::Error;
use geojson::{GeoJson,FeatureCollection,Feature,Value};
use serde_json;
use structopt::StructOpt;
use chrono::{DateTime,Utc,Datelike,Timelike};

#[macro_use]
extern crate lazy_static;

/// structopt command line arguments
#[derive(StructOpt,Clone,Debug)]
pub struct Arguments {
    /// only report what features files would be written
    #[structopt(long)]
    dry_run: bool,

    #[structopt(short,long)]
    verbose: bool,

    /// directory where to create output
    #[structopt(short,long,default_value="output")]
    output_dir: String,

    /// pathnames of *.geojson files to process
    input_files: Vec<String>,
}

lazy_static! {
    #[derive(Debug)]
    static ref ARGS: Arguments = Arguments::from_args();
}

fn check_subdir (output_dir: &str, sub_dir: &str) -> io::Result<()> {
    let mut pb = PathBuf::new();
    pb.push(output_dir);
    pb.push(sub_dir);

    let path = pb.as_path();
    if path.is_dir() {
        Ok(())
    } else {
        fs::create_dir(&path)
    }
}

fn check_output_subdirs <'a> (output_dir: &'a str) -> Result<&'a str,String> {
    if check_subdir(output_dir, "polygons").is_ok()
            && check_subdir(output_dir, "lines").is_ok()
            && check_subdir(output_dir, "points").is_ok() {
        Ok(output_dir)
    } else { 
        Err(format!("could not create geometry sub-directories in {}",output_dir))
    }
}

fn check_output_dir <'a> (path_str: &'a str) -> Result<&'a str,String> {
    let path = Path::new(path_str);

    if path.is_dir() {
        let md = fs::metadata(&path).unwrap();
        if md.permissions().readonly() {
            Err(format!("output_dir {:?} not writable", &path))
        } else {
            check_output_subdirs(path_str)
        }

    } else {
        match fs::create_dir(&path) {
            Result::Ok(_) =>  
                check_output_subdirs(path_str),
            Err(e) => {
                Err(format!("failed to create output_dir {:?}", &path))
            }
        }
    }
}

fn feature_directory (feature: &Feature) -> Option<&'static str> {
    feature.geometry.as_ref().and_then( |geom| {
        match geom.value {
            geojson::Value::MultiPolygon(_)    | geojson::Value::Polygon(_)    => Some("polygons"),
            geojson::Value::MultiLineString(_) | geojson::Value::LineString(_) => Some("lines"),
            geojson::Value::MultiPoint(_)      | geojson::Value::Point(_)      => Some("points"),
            _ => None,
        }
    })
}

fn datetime_property_of_feature (feature: &Feature) -> Option<&'static str> {
    feature.geometry.as_ref().and_then( |geom| {
        match geom.value {
            geojson::Value::MultiPolygon(_)    | geojson::Value::Polygon(_)    => Some("PolygonDateTime"),
            geojson::Value::MultiLineString(_) | geojson::Value::LineString(_) => Some("LineDateTime"),
            geojson::Value::MultiPoint(_)      | geojson::Value::Point(_)      => Some("PointDateTime"),
            _ => None,
        }
    })
}

fn get_datetime (feature: &Feature) -> Option<DateTime<Utc>> {
    if let Some(dt_property_name) = datetime_property_of_feature(feature) {
        if let Some(serde_json::Value::String(s)) = feature.property(dt_property_name){
            return DateTime::parse_from_rfc3339(s.as_str()).map( |dt| {dt.with_timezone(&Utc)}).ok();
        }
        //else { ... }
    }

    None
}

fn write_feature_file (feature: &Feature, output_dir: &str, sub_dir: &str, dt: &DateTime<Utc>) {
    // TODO - should start with FeatureCategory based prefix (can't use FeatureCategory directly since it contains spaces)
    let filename = format!("{}-{:02}-{:02}_{:02}{:02}.geojson", dt.year(),dt.month(),dt.day(),dt.hour(),dt.minute());

    let mut pb = PathBuf::new();
    pb.push(output_dir);
    pb.push(sub_dir);
    pb.push(filename);

    if !ARGS.dry_run {
        // TODO - check if file already exists, append suffix and print warning

        match File::create(&pb) {
            Ok(file) => {
                print!("\n-- writing file {:?}..", pb);
                let writer = io::BufWriter::new(file);
                match geojson::ser::to_feature_writer(writer,feature) {
                    Ok(()) => println!("Ok."),
                    Err(e) => eprintln!("failed to write feature file: {:?}", e),
                }
            },
            Err(e) => eprintln!("-- failed to create feature file {:?} : {:?}", pb, e),
        }
    } else {
        println!("-- would write file {:?}", pb);
    }
}

// TODO - these are begging for a mondaic if_let!(...) macro

fn process_geojson(gj: &GeoJson, output_dir: &str) {
    if let GeoJson::FeatureCollection(ref fc) = *gj {
        for feature in &fc.features {
            if let Some(ref properties) = feature.properties {
                if let Some(dt) = get_datetime(&feature) {
                    if let Some(sub_dir) = feature_directory(&feature) {
                        write_feature_file( &feature, output_dir, sub_dir, &dt)
                    } else { println!(" --ignoring feature without relevant geometry type:{:?}\n", properties) }
                } else { println!("-- ignoring feature without DateTime property:\n{:?}", properties); }
            } else { println!("-- skipping feature without properties\n{:?}", feature); }
        }
    } else { eprintln!("geojson not a FeatureCollection"); }
}

fn main() -> Result<(),Box<dyn Error>> {
    if let Ok(output_dir) = check_output_dir(ARGS.output_dir.as_str()) {
        for file_name in &ARGS.input_files {
            if let Ok(mut file) = File::open(file_name.as_str()) {
                let mut contents = String::new();
                if let Ok(len) = file.read_to_string(&mut contents) {
                    if let Ok(geojson) = contents.parse::<GeoJson>() {
                        process_geojson(&geojson, output_dir);
                    } else { eprintln!("file contents of {} not valid GeoJSON", file_name) }
                } else { eprintln!("could not read file contents of {}", file_name) }
            } else { eprintln!("could not open file {}", file_name) }
        }
    } else { eprintln!("could not open output dir {}", ARGS.output_dir) }

    Ok(())
}
