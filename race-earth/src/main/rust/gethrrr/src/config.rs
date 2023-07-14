use serde::{Serialize,Deserialize};
use std::fs;
use ron;

/// command line arguments
#[derive(StructOpt,Clone,Debug)]
pub struct Arguments {
       /// just compute schedule, do not download files
       #[structopt(long)]
       schedule_only: bool,
   
       /// name of example config file to generate (no downloads)
       #[structopt(long)]
       generate_config: String,

       /// if true no HRRR files will be downloaded (useful to debug schedule)
       #[structopt(long)]
       dry_run: bool,
   
       /// increase logging level (can also be set with RUST_LOG env var)
       #[structopt(short,long)]
       verbose: bool,

       /// path of config file to run or generate
       config_file: String
}

#[derive(Debug,Serialize,Deserialize)]
enum DurationUnit {
    Seconds(i64),
    Minutes(i64),
    Hours(i64)
}

impl DurationUnit {
    fn num_seconds (&self) -> i64 {
        match *self {
            Seconds(n) => n,
            Minutes(n) => n * 60,
            Hours(n) => n * 3600
        }
    }
}

impl From<DurationUnit> for chrono::Duration {
    fn from (du: DurationUnit) -> chrono::Duration {
      chrono::Duration::seconds(du.num_seconds())
    }
}


/// configured values from config file
#[derive(Debug,Serialize,Deserialize,Default)]
pub struct Config {

    /// NOAA server url for retrieving HRRR forecast files 
    hrrr_url: String,

    /// NOAA server url pattern for directory listing of available forecast files
    hrrr_dir_url: String,

    /// directory to store downloaded HRRR forecast files
    output_dir: String,

    /// HRRR field names to include in report requests
    forecast_fields: Vec<String>, 

    /// HRRR altitude levels to include for forecast_fields
    forecast_levels: Vec<String>,

    /// NOAA forecast region (conus,AL)
    region: String,

    /// symbolic name for bbox
    bbox_name: String,

    /// geographic boundaries of forecast regions
    bbox: BoundingBox,

    /// download delay in minutes, used to compute schedule
    download_delay: DurationUnit,

    /// max age (in hours) after which report files are deleted. If 0 (default) they are never automatically deleted
    max_age: DurationUnit,

    /// max attempts to retrieve not yet available file
    max_retry: u32,

    /// delay in seconds before next attempt to retrieve not yet available file
    retry_delay: DurationUnit
}

//--- auxiliary config types

/// simple geographic bounding box (all boundaries are in decimal degrees)
#[derive(Debug,Serialize,Deserialize)]
pub struct BoundingBox {
    west: f64,
    south: f64,
    east: f64,
    north: f64
}


/// our default values for Config
impl Default for Config {
    fn default () -> Self {
        Config {
            hrrr_url: "https://nomads.ncep.noaa.gov/cgi-bin/filter_hrrr_2d.pl".to_string(),
            hrrr_dir_url: "https://nomads.ncep.noaa.gov/pub/data/nccf/com/hrrr/prod/hrrr.${yyyyMMdd}/conus/".to_string(),
            output_dir: "hrrr".to_string(),
            forecast_fields: vec!(
                "TCDC".to_string(),
                "TMP".to_string(),
                "UGRD".to_string(),
                "VGRD".to_string()
            ), 
            forecast_levels: vec!(
                "lev_2_m_above_ground".to_string(),
                "lev_10_m_above_ground".to_string(),
                "lev_entire_atmosphere".to_string()
            ),
            region: "conus".to_string(),
            bbox_name: "west".to_string(),
            bbox: BoundingBox {
                west: -124.0, 
                south: 22.0, 
                east: -104.0, 
                north: 50.0
            },
            download_delay: Minutes(2),
            max_age: Hours(2),
            max_retry: 10,
            retry_delay: Seconds(60)
        }
    }
}


lazy_static! {
    #[derive(Debug)]
    pub static ref ARGS: Arguments = Arguments::from_args();
    pub static ref CONFIG: Config = load_config_file();

    pub static ref STATIC_QUERY: String = get_static_query(); // derived from CONFIG
}

fn load_config_file() -> Config {
    let filename: &str = &ARGS.config_file;
    let contents = match fs::read_to_string(filename) {
        Ok(c) => c,
        Err(_) => {
            eprintln!("could not read config file `{}`", filename);
            exit(1);
        }
    };
    match toml::from_str(&contents) {
        Ok(d) => d,
        Err(_) => {
            eprintln!("invalid config file contents `{}`", filename);
            exit(1);
        }
    }
}

/// get the static part of the query string from OPT
/// the bbox, vars and level info is configured and does not change across files
fn get_static_query() -> String {
    let mut s = format!("subregion=&toplat={}&leftlon={}&rightlon={}&bottomlat={}", 
                      CONFIG.bbox.north, CONFIG.bbox.west, CONFIG.bbox.east, CONFIG.bbox.south);

    for v in CONFIG.forecast_fields.iter() {
        s.push('&');
        s.push_str("var_");
        s.push_str(v.as_str());
        s.push_str("=on");
    }

    for v in CONFIG.forecast_levels.iter() {
        s.push('&');
        s.push_str(v.as_str());
        s.push_str("=on");
    }

    s
}