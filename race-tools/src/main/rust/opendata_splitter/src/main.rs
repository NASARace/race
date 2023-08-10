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

#[macro_use]
extern crate lazy_static;

use std::{fs, io};
use std::collections::HashSet;
use std::default::Default;
use std::error::Error;
use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::result::Result;

use chrono::{Datelike, DateTime, Timelike, Utc};
use chrono::naive::NaiveDate;
use geojson::{Feature, FeatureCollection, GeoJson, Value};
use serde::{Deserialize, Serialize};
use serde_json;
use structopt::StructOpt;
use uom::si::area::acre;
use indexmap::IndexMap;

#[macro_use]
//extern crate uom;
use uom::si::f64::*;

// odin's own imports
use odin_common::fs::{ensure_writable_dir, existing_non_empty_file, file_contents_as_string, set_filepath_contents, set_filepath_contents_with_backup};
use odin_common::datetime::{is_between_inclusive,ser_short_rfc3339,parse_utc_datetime_from_date};
use odin_common::macros::if_let;

/// structopt command line arguments
#[derive(StructOpt,Clone,Debug)]
struct CliOpts {
    /// only report which feature files would be written (do not store files)
    #[structopt(long)]
    dry_run: bool,

    #[structopt(short,long)]
    verbose: bool,

    /// if set, use CreateDate or DateCurrent if {Polygon,Line,Point}DateTime is not set or has null value
    #[structopt(long)]
    estimate_date: bool,

    /// optional start date filter in yyyy-mm-dd format (interpreted as UTC, used to initialize new summary.json)
    #[structopt(long,parse(from_os_str = parse_utc_datetime_from_date))]
    start_date: Option<DateTime<Utc>>,

    /// optional end date filter in yyyy-mm-dd format (interpreted as UTC, used to initialize new summary.json)
    #[structopt(long,parse(from_os_str = parse_utc_datetime_from_date))]
    end_date: Option<DateTime<Utc>>,

    //--- output options

    /// if set, store all feature properties found in input. If not set store only feature specific subset
    #[structopt(long)]
    keep_properties: bool,

    /// directory to store generated files in (defaults to fire name)
    #[structopt(short,long)]
    output_dir: Option<String>,

    //--- positional input args

    /// name of fire (also used for output dir if not set explicitly)
    fire_name: String,

    /// pathnames of *.geojson input files to process
    input_files: Vec<String>,
}

lazy_static! {
    #[derive(Debug)]
    static ref ARGS: CliOpts = CliOpts::from_args();
}

/// timestamped feature file entry (stored in summary)
#[derive(Serialize,Deserialize,Debug)]
struct PerimeterFile {
    date: DateTime<Utc>,
    method: String,
    filename: String
}

/// internal (per-run) data to filter perimeters
#[derive(Debug)]
struct PerimeterFilterData {
    object_ids: HashSet<String>,
    shape_lengths: HashSet<String>,
    dates: HashSet<DateTime<Utc>>
}

impl Default for PerimeterFilterData {
    fn default() -> Self {
        PerimeterFilterData {
            object_ids: HashSet::new(),
            shape_lengths: HashSet::new(),
            dates: HashSet::new()
        }
    }
}

/// summary of fire information, stored in <fire-name>/summary.json.
/// this is the primary model of the data we want to collect
/// if this file exists it is read during program start
#[derive(Serialize,Deserialize,Debug)]
struct FireSummary {
    name: String,  // 'IncidentName' (mixed upper/lower case)

    #[serde(serialize_with="ser_short_rfc3339")]
    last_modified: DateTime<Utc>,

    irwin_id: String, // 'IRWINID'
    inciweb_id: String,

    #[serde(serialize_with="ser_short_rfc3339")]
    start: DateTime<Utc>,

    #[serde(serialize_with="ser_short_rfc3339")]
    contained: DateTime<Utc>,

    #[serde(serialize_with="ser_short_rfc3339")]
    end: DateTime<Utc>,

    location: geojson::Position,
    size: Area, // final size

    //--- fire perimeters
    #[serde(with = "indexmap::map::serde_seq")]
    perimeters: IndexMap<String,PerimeterFile>,
    #[serde(skip)]
    perimeter_filters: PerimeterFilterData, // for internal purposes

    //... and many more feature categories to come
}

impl Default for FireSummary {
    fn default() -> Self {
        let now = Utc::now();
        FireSummary {
            name: ARGS.fire_name.clone(),
            last_modified: now,
            irwin_id: "".to_string(),
            inciweb_id: "".to_string(),
            start: ARGS.start_date.unwrap_or_default(),
            contained: ARGS.end_date.unwrap_or(Utc::now()),
            end: ARGS.end_date.unwrap_or(Utc::now()),
            location: vec![],
            size: Default::default(),
            perimeters: IndexMap::new(),
            perimeter_filters: Default::default()
        }
    }
}

fn main() -> Result<(),Box<dyn Error>>{
    let output_dir = if let Some(ref path) = ARGS.output_dir { path.as_str() } else { ARGS.fire_name.as_str() };
    ensure_writable_dir(output_dir)?;
    let mut summary = get_summary(output_dir)?;

    for file_name in &ARGS.input_files {
        if_let! {
            Ok(mut file) = File::open( file_name.as_str()) , e=>eprintln!("opening input file {} failed: {:?}", file_name,e);
            Ok(contents) = file_contents_as_string(&mut file) , eprintln!("could not read content of input file {}", file_name);
            Ok(gj) = contents.parse::<GeoJson>() , e=>eprintln!("input file {} not valid GeoJSON: {:?}", file_name,e) => {
                println!("processing input file {:}", file_name);
                process_geojson( &gj, output_dir, &mut summary)
            }
        }
    }

    println!("storing {}/summary.json", output_dir);
    store_summary(&summary, output_dir)?;
    Ok(())
}

fn get_summary (output_dir: &str) -> Result<FireSummary,Box<dyn Error>> {
    if let Ok(mut file) = existing_non_empty_file(output_dir, "summary.json") {
        let data = file_contents_as_string(&mut file)?;
        Ok(serde_json::from_str::<FireSummary>(data.as_str())?)
    } else {
        println!("no {}/summary.json found, generating default",output_dir);
        Ok(Default::default()) // just a template
    }
}

fn store_summary (summary: &FireSummary, output_dir: &str) -> io::Result<()> {
    let new_contents: String = serde_json::to_string_pretty(summary)?;
    set_filepath_contents_with_backup( output_dir, "summary.json", ".bak", new_contents.as_bytes())
}

fn process_geojson (gj: &GeoJson, output_dir: &str, summary: &mut FireSummary) {
    if let GeoJson::FeatureCollection(ref fc) = *gj {
        for feature in &fc.features {
            if_let! {
                Some(ref props) = feature.properties, eprintln!("ignoring feature without properties");
                Some(id) = archive_id_of_feature(feature), eprintln!("ignoring feature without archive id");
                Some(cat) = cat_property_of_feature(feature), eprintln!("ignoring feature #{} without category", id);
                Some(dt) = get_datetime(feature), eprintln!("ignoring feature #{} without date", id) => {
                    match process_feature(  feature, cat.as_str(), &dt, output_dir, summary) {
                        Ok(true) => println!("stored {} feature #{} at {}", cat, id, dt),
                        Ok(false) => println!("dropped {} feature #{} at {}", cat, id, dt),
                        Err(e) => eprintln!("error storing {} feature #{}", cat, id)
                    }
                }
            }
        }
    } else { eprintln!("geojson not a FeatureCollection"); }
}

fn process_feature (feature: &Feature, cat: &str, dt: &DateTime<Utc>, output_dir: &str, summary: &mut FireSummary)
                                                                                      -> Result<bool,Box<dyn Error>> {
    match cat {
        "wildfire_daily_fire_perimeter" => process_perimeter(feature,cat,dt,output_dir,summary),
        //... and many more
        _ => Ok(false) // ignore
    }
}

//--- the FeatureCategory specific filter/store functions

fn process_perimeter (feature: &Feature, cat: &str, dt: &DateTime<Utc>, output_dir: &str, summary: &mut FireSummary)
                                                                                      -> Result<bool,Box<dyn Error>> {
    let filename = format!("{}-{:02}-{:02}_{:02}{:02}_{}.geojson",
                           dt.year(),dt.month(),dt.day(),dt.hour(),dt.minute(),cat);

    // TODO - this should also check for "near duplicates" for which dt < x minutes

    if !filter_perimeter(feature, cat, dt, summary) { return Ok(false); }

    write_feature_file(feature, &filename, output_dir)?;

    let key = filename.clone();
    let pf = PerimeterFile {
        date: dt.clone(),
        method: string_property_of_feature_or_else(feature,"MapMethod", "unknown"),
        filename
    };
    summary.perimeters.insert(key, pf); // last entry wins
    summary.last_modified = Utc::now();

    Ok(true)
}

fn filter_perimeter (feature: &Feature, cat: &str, dt: &DateTime<Utc>, summary: &mut FireSummary) -> bool {
    if !filter_date(dt, summary) { return false; }

    let fd: &mut PerimeterFilterData = &mut summary.perimeter_filters;
    if let Some(oid) = string_property_of_feature(feature, "OBJECTID") {
        if !fd.object_ids.insert(oid) { return false; } // was already in the set
    }

    if let Some(shape_length) = f64_property_of_feature(feature, "SHAPE_length") {
        let len_hash = format!("{}", shape_length); // we only need this within the same run so it's a safe approximation
        if !fd.shape_lengths.insert(len_hash) { return false; }
    }

    //... and possibly more

    true
}


//--- generic feature filters

fn filter_date (dt: &DateTime<Utc>, summary: &FireSummary) -> bool {
    if let Some(ref start) = ARGS.start_date {
        if dt < start { return false; }
    }
    if let Some(ref end) = ARGS.end_date {
        if dt > end { return false; }
    }

    if *dt < summary.start { return false; }
    if *dt > summary.end { return false; }

    true
}

//--- feature IO and property access

fn write_feature_file (feature: &Feature, filename: &str, output_dir: &str) -> Result<(),Box<dyn Error>>{
    let mut pb = PathBuf::new();
    pb.push(output_dir);
    pb.push(filename);

    if !ARGS.dry_run {
        // TODO - check if file already exists, append suffix and print warning
        let file = File::create(&pb)?;
        let writer = io::BufWriter::new(file);
        geojson::ser::to_feature_writer(writer,feature)?;
    }
    Ok(())
}

/// get date to use for time-sorted entry of feature. Depending on geometry this should be
/// 'PolygonDateTime', 'LineDateTime' or 'PointDateTime' but these sometimes have 'null' values in
/// which case we optionally fall back to CreateDate or DateCurrent
fn get_datetime (feature: &Feature) -> Option<DateTime<Utc>> {
    let d_geom = geodate_property_of_feature(feature).and_then(|pn| datetime_of_feature(feature, pn));

    if !ARGS.estimate_date { // don't try to deduce {Polygon,Line,Point}DateTime from other dates
        d_geom

    } else {
        if d_geom.is_some() { return d_geom; }  // geom date always has precedence

        let d_create = datetime_of_feature(feature,"CreateDate");
        if d_create.is_some() { return d_create; }

        let d_current = datetime_of_feature(feature,"DateCurrent");
        d_current  // otherwise we are out of luck
    }
}

fn datetime_of_feature (feature: &Feature, prop_name: &str) -> Option<DateTime<Utc>> {
    string_property_of_feature(feature,prop_name).and_then(|sr| parse_date(&sr))
}

// this is supposed to be unique for each feature (regardless of duplications)
fn archive_id_of_feature (feature: &Feature) -> Option<i64> {
    i64_property_of_feature(feature, "GDB_ARCHIVE_OID")
}

fn string_property_of_feature (feature: &Feature, prop_name: &str) -> Option<String> {
    match feature.property(prop_name) {
        Some(serde_json::Value::String(s)) => Some(s.to_string()),
        _ => None
    }
}

fn i64_property_of_feature (feature: &Feature, prop_name: &str) -> Option<i64> {
    match feature.property(prop_name) {
        Some(serde_json::Value::Number(n)) => n.as_i64(),
        _ => None
    }
}

fn string_property_of_feature_or_else (feature: &Feature, prop_name: &str, default_value: &str) -> String {
    string_property_of_feature(feature,prop_name).or( Some(default_value.to_string())).unwrap()
}

fn f64_property_of_feature (feature: &Feature, prop_name: &str) -> Option<f64> {
    match feature.property(prop_name) {
        Some(serde_json::Value::Number(n)) => n.as_f64(),
        _ => None
    }
}

fn geodate_property_of_feature(feature: &Feature) -> Option<&'static str> {
    feature.geometry.as_ref().and_then( |geom| {
        match geom.value {
            geojson::Value::MultiPolygon(_)    | geojson::Value::Polygon(_)    => Some("PolygonDateTime"),
            geojson::Value::MultiLineString(_) | geojson::Value::LineString(_) => Some("LineDateTime"),
            geojson::Value::MultiPoint(_)      | geojson::Value::Point(_)      => Some("PointDateTime"),
            _ => None,
        }
    })
}

fn parse_date(spec: &String) -> Option<DateTime<Utc>> {
    if spec != "null" {
        DateTime::parse_from_rfc3339(spec.as_str()).map( |dt| {dt.with_timezone(&Utc)}).ok()
    } else { None }
}

/// get a canonical filesystem compatible version of the 'FeatureCategory' property  (if any)
/// (all lowercase, '_' for spaces and '/')
fn cat_property_of_feature(feature: &Feature) -> Option<String> {
    if let Some(serde_json::Value::String(s)) = feature.property("FeatureCategory"){
        let mut cat = s.replace(" ", "_");
        cat = cat.replace("/", "_or_");
        cat.make_ascii_lowercase();
        Some(cat)
    } else { None }
}