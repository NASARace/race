#![allow(unused)]

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

 /// gethrrr - program to coninuously retrieve filtered HRRR weather reports from NOAA servers
 /// 
 /// 1. Forecast Coverage
 /// HRRR reports are generated each hour for a range of fixed forecast hours (each forecast hour represented by a separate file). We call
 /// the set of all forecast files for a given hour a 'cycle', and the hour for which this cycle is the 'base hour'.
 /// Base Hours 00,06,12,18 are extended cycles covering [0..48h] (=number of forecast files to retrieve), all other (regular) cycles
 /// cover [0..18h].
 /// 
 /// +---------------------------> forecast steps (hours)
 /// |  ...
 /// |   ------------------ (reg)
 /// |    ------------------------------------------------ (ext)
 /// |     ------------------ (reg)
 /// |      ------------------ (reg)
 /// |       ...
 /// V base hour
 /// 
 /// 2. Forecast Availability
 /// File availability within a cycle is staggered (about 50min from base hour until first step becomes available, about 2min between
 /// each step file). These 'schedules' are fairly constant during a day but vary between regular and extended cycles. File availability 
 /// between an extended and the following regular cycle can overlap. 
 ///
 ///      base      base+1    base+2
 /// ..-----|---------|---------|---------|---> t
 ///        |.......==|====     |         |          regular cycle
 ///                  |.......==|======== |          extended cycle
 ///                            |.......==|====      regular cycle
 /// 
 /// Schedules can be computed by parsing the directory listing from the NOAA server (always has directories for current and past day).
 /// 
 /// 3. Initialization
 /// Depending on the query (size of area and number of fields) forecast files can be large (>1G) and we want to minimize bandwidth and
 /// requests. While gethrrr is assumed to be long running (keeping only a limited history of past forecast files) it should upon start
 /// try to download the largest available forecast span in the shortest amount of time (minimal overlap between current and previous
 /// cycles):
 /// 
 ///     ---------------------|========================== most recent extended cycle       '-': outdated (ignored) step
 ///      ...                 |                                                            '=': available step to download
 ///         ------|=========== previous (fully available) cycles                          '*': future step to download
 ///          ======************ current cycle
 ///               ^
 ///               init time
 /// 
 /// references:
 ///  [1]: https://nomads.ncep.noaa.gov/ - available data products
 ///  [2]: https://nomads.ncep.noaa.gov/gribfilter.php?ds=hrrr_2d - HRRR queries
 ///  [3]: https://nomads.ncep.noaa.gov/pub/data/nccf/com/hrrr/prod/ - HRRR download directories

use std::str::FromStr;
use structopt::StructOpt;
use std::fmt::Display;
use std::fmt::Write as FmtWrite;
use std::io::Write as IoWrite;
use std::time::SystemTime;
use chrono::{DateTime,Datelike,Timelike,Utc,SecondsFormat};
use http;
use reqwest;
use std::{io,fs,time,sync};
use std::path::{Path,PathBuf};
use regex::Regex;
use tempfile;
use tokio::time::{Duration,Sleep};
use anyhow::{anyhow,Context,Result,Ok};
use log::{debug, info, warn, error, log_enabled, Level};
use odin_common::{
    datetime::{elapsed_minutes_since},
    strings::{mk_string}
};

#[macro_use]
extern crate lazy_static;


/// command line argument structure
#[derive(StructOpt,Clone,Debug)]
struct Opt {

    /// url of server for filtered HRRR forecast files
    #[structopt(long,default_value="https://nomads.ncep.noaa.gov/cgi-bin/filter_hrrr_2d.pl")]
    hrrr_url: String,

    /// url pattern for directory listing of available forecast files
    #[structopt(long,default_value="https://nomads.ncep.noaa.gov/pub/data/nccf/com/hrrr/prod/hrrr.${yyyyMMdd}/conus/")]
    hrrr_dir_url: String, 

    /// directory to store downloaded HRRR forecast files
    #[structopt(long,default_value="hrrr")]
    output_dir: String,

    /// comma separated list of grid field names to retrieve
    #[structopt(long,default_value="TCDC,TMP,UGRD,VGRD",use_delimiter=true)]
    fields: Vec<String>,

    /// comma separated list of grid levels to retrieve
    #[structopt(long,default_value="lev_2_m_above_ground,lev_10_m_above_ground,lev_entire_atmosphere",use_delimiter=true)]
    levels: Vec<String>,

    /// region name ('alaska' or 'conus' - has to be consistent with bbox_.. subregion)
    #[structopt(long,default_value="conus")]
    region: String,

    #[structopt(long,default_value="west")]
    bbox_name: String,

    /// boundary west
    #[structopt(long,default_value="-124.0")]
    bbox_west: f64,

    /// boundary south
    #[structopt(long,default_value="22.0")]
    bbox_south: f64,

    /// boundary east
    #[structopt(long,default_value="-104.0")]
    bbox_east: f64,

    /// boundary north
    #[structopt(long,default_value="50.0")]
    bbox_north: f64,

    /// download delay in minutes, used to compute schedule
    //#[structopt(long,default_value="2")]
    #[structopt(long,default_value=config_delay_minutes())]
    delay_minutes: u32,

    /// max age (in hours) after which report files are deleted. If 0 (default) they are never automatically deleted
    #[structopt(long,default_value="2")]
    max_age: u64,

    /// max attempts to retrieve not yet available file
    #[structopt(long,default_value="10")]
    max_retry: usize,

    /// delay in seconds before next attempt to retrieve not yet available file
    #[structopt(long,default_value="60")]
    retry_secs: u32,

    /// just compute schedule, do not download files
    #[structopt(long)]
    schedule_only: bool,

    /// if true no HRRR files will be downloaded (useful to debug schedule)
    #[structopt(long)]
    dry_run: bool,

    /// increase logging level (can also be set with RUST_LOG env var)
    #[structopt(short,long)]
    verbose: bool,
}


lazy_static! {
    #[derive(Debug)]
    static ref OPT: Opt = Opt::from_args();

    static ref STATIC_QUERY: String = get_static_query(); // derived from OPT
}

/// get the static part of the query string from OPT
/// the bbox, vars and level info is configured and does not change across files
fn get_static_query() -> String {
    let mut s = format!("subregion=&toplat={}&leftlon={}&rightlon={}&bottomlat={}", 
                      OPT.bbox_north, OPT.bbox_west, OPT.bbox_east, OPT.bbox_south);

    for v in OPT.fields.iter() {
        s.push('&');
        s.push_str("var_");
        s.push_str(v.as_str());
        s.push_str("=on");
    }

    for v in OPT.levels.iter() {
        s.push('&');
        s.push_str(v.as_str());
        s.push_str("=on");
    }

    s
}

//--- arg utility funcs



//--- time utility funcs

fn full_hour_dt (dt: &DateTime<Utc>) -> DateTime<Utc> {
    dt.with_minute(0)
        .and_then(|dt| dt.with_second(0))
        .and_then(|dt| dt.with_nanosecond(0))
        .unwrap()
}

fn last_extended_forecast (dt: &DateTime<Utc>) -> DateTime<Utc> {
    let fh = full_hour_dt(dt);
    let dh = fh.hour() % 6;

    if dh > 0 {
        fh - chrono::Duration::hours(dh as i64)
    } else {
        fh
    }
}

fn is_extended_forecast (dt: &DateTime<Utc>) -> bool {
    dt.hour() % 6 == 0
}

fn hours (h: u32) -> chrono::Duration {
    chrono::Duration::hours(h as i64)
}

fn minutes (m: u32) -> chrono::Duration {
    chrono::Duration::minutes(m as i64)
}

fn fmt_date(dt: &DateTime<Utc>) -> String {
    dt.to_rfc3339_opts(SecondsFormat::Secs, true)
}

async fn sleep_secs (secs: u32) {
    if secs > 0 {
        tokio::time::sleep( tokio::time::Duration::from_secs( secs as u64)).await
    }
}



async fn wait_for (minutes: u32) {
    if minutes > 0 {
        info!("sleeping for {} min..", minutes);
        sleep_secs( minutes * 60).await;
    }
}

async fn wait_for_schedule (base: &DateTime<Utc>, scheduled: u32) {
    let elapsed = elapsed_minutes_since(base);
    if elapsed < scheduled {
        wait_for(scheduled - elapsed).await;
    }
}

//--- input validation


//--- output cleanup

// we need a function that returns a result or this becomes quite unwieldy
fn remove_outdated_files (now: time::SystemTime) -> Result<()> {
    if OPT.max_age > 0 {
        info!("-- removing old files");
        let max_age_secs: u64 = OPT.max_age * 3600; // OPT.max_age is in hours

        for dir_entry in fs::read_dir(OPT.output_dir.as_str())? {
            let path = dir_entry?.path();
            if (path.ends_with(".grib2")) {
                let created = fs::metadata(&path)?.created()?;
                if let Result::Ok(age) = now.duration_since(created) {
                    if age.as_secs() > max_age_secs {
                        match fs::remove_file(&path) {
                            Result::Ok(_) => info!("removed old {:?}", path),
                            Err(e) => warn!("failed to remove old {:?}: {:?}", path, e)
                        } 
                    }
                }
            }
        }
    }
    Ok(())
}

fn check_outdated(maybe_last_check: Option<SystemTime>) -> SystemTime {
    let now = time::SystemTime::now();

    match maybe_last_check {
        Some(last_check) => {
            if let Result::Ok(dur) = now.duration_since(last_check) {
                if dur.as_secs() > 3600 {
                    remove_outdated_files(now);
                    return now;
                }
            }
            last_check
        },
        None => {
            remove_outdated_files(now);
            now
        }
    }
}

//--- schedule functions

/// check if schedule is a monotonic sequence of valid delays in minutes < 60
fn check_schedule(sched: &Vec<u32>) -> bool {
    if sched.is_empty() {
        error!("schedule is empty\n");
        false
    } else {
        // note that we have to check in chunks since the first extended download schedule might not be monotonic (we only get 4 of them)

        let len = sched.len();
        for i in 1..len {  // continuous forecasts for each hour (all schedules have to fit into 60min)
            if sched[i] < sched[i-1] { 
                error!("schedule not monotonic [{}] = {}", i, sched[i]);
                return false;
            }
        }

        true
    }
}

fn update_schedule (avg_schedule: &mut Vec<u32>, max_schedule: &mut Vec<u32>, data_points: &mut Vec<u32>,
                    bh: usize, fch: usize, h: usize, m: usize, diff_minutes: u32) {
    if fch >= avg_schedule.len() { // first one for this forecast hour
        avg_schedule.resize(fch+1, diff_minutes);
        max_schedule.resize(fch+1, diff_minutes);
        data_points.resize(fch+1, 1);

    } else {
        data_points[fch] += 1;
        if diff_minutes > max_schedule[fch] {
            max_schedule[fch] = diff_minutes;
        }
        if diff_minutes > avg_schedule[fch] {
            avg_schedule[fch] += (diff_minutes - avg_schedule[fch])/data_points[fch] as u32;
        } else {
            avg_schedule[fch] -= (avg_schedule[fch] - diff_minutes)/data_points[fch] as u32;
        }
    }
}

// get schedules for both regular (18h) and extended (48h) forecast cycles
fn parse_schedules (txt: &String) -> Result<(Vec<u32>,Vec<u32>)> {
    // watch out - the HTML format for HRRR dir listings might change
    // current line format: "hrrr.t00z.wrfnatf01.grib2                   19-Jun-2023 00:54  703M"
    let re = Regex::new(r#"\.grib2">hrrr\.t(\d{2})z.wrfsfcf(\d{2}).grib2</a>\s+(\d+)-(.+)-(\d{4})\s+(\d{2}):(\d{2})\s"#).unwrap();

    let mut avg_reg_schedule: Vec<u32> = Vec::new();
    let mut max_reg_schedule: Vec<u32> = Vec::new();
    let mut reg_data_points: Vec<u32> = Vec::new();

    let mut avg_ext_schedule: Vec<u32> = Vec::new();
    let mut max_ext_schedule: Vec<u32> = Vec::new();
    let mut ext_data_points: Vec<u32> = Vec::new();

    // get average of availability delays in minutes
    for cap in re.captures_iter(txt.as_str()) {
        if cap.len() == 8 {
            // regex makes sure those are valid numbers, cap[0] is whole match
            let bh: usize = cap[1].parse().unwrap();  // base hour (number of data points for fch) == HRRR "model cycle runtime hour"
            let fch: usize = cap[2].parse().unwrap(); // forecast hour
            let h: usize = cap[6].parse().unwrap(); // file creation minute
            let m: usize = cap[7].parse().unwrap(); // file creation minute

             // duration in minutes from begin of forecast cycle to availability of file
            let diff_minutes: u32 = if h >= bh {(h - bh)*60 + m} else {(h+24 - bh)*60 + m} as u32;

            if bh % 6 == 0 {
                update_schedule(&mut avg_ext_schedule, &mut max_ext_schedule, &mut ext_data_points, bh, fch, h, m, diff_minutes);
            } else {
                update_schedule(&mut avg_reg_schedule, &mut max_reg_schedule, &mut reg_data_points, bh, fch, h, m, diff_minutes);
            }
        }  
    }

    if avg_reg_schedule.is_empty() || avg_ext_schedule.is_empty() { 
        Err(anyhow!("unexpected directory content - at least one schedule is empty"))
    } else {
        if OPT.delay_minutes > 0 {
            for i in 0..avg_reg_schedule.len() { avg_reg_schedule[i] += OPT.delay_minutes; }
            for i in 0..avg_ext_schedule.len() { avg_ext_schedule[i] += OPT.delay_minutes; }
        }

        info!("regular schedule (steps in minutes):  {}", mk_string(avg_reg_schedule.iter(),","));
        info!("extended schedule (steps in minutes): {}", mk_string(avg_ext_schedule.iter(),","));

        Ok( (avg_reg_schedule,avg_ext_schedule) )
    }
}

async fn get_schedules (url_template: &str) -> anyhow::Result<(Vec<u32>,Vec<u32>)> {
    let now = chrono::offset::Utc::now();
    let dt = if now.hour() < 12 { now - chrono::Duration::hours((now.hour() + 1).into()) } else { now }; // use prev day if not enough data points yet

    let date_spec = format!("{:04}{:02}{:02}", dt.year(), dt.month(), dt.day());
    let url = url_template.replace( "${yyyyMMdd}", date_spec.as_str());

    let response = reqwest::get(&url).await.with_context(|| format!("get {} failed", url))?;
    match response.status() {
        reqwest::StatusCode::OK => {
            let txt = response.text().await?;
            parse_schedules(&txt)
        }
        code => Err(anyhow!(format!("request failed with status {}", code.as_u16())))
    }
}

//--- forecast file retrieval

/// generate hrrr filename for given base hour and forecast step (hour from base hour)
fn get_filename (dt: &DateTime<Utc>, step: usize) -> String {
    format!("hrrr-wrfsfcf-{}-{}-{:4}{:02}{:02}-{:02}+{:02}.grib2", OPT.region, OPT.bbox_name, dt.year(),dt.month(),dt.day(),dt.hour(), step)
} 

/// download a single file for given base date and forecast step
async fn download_file (dt: &DateTime<Utc>, step: usize) -> Result<()> {
    let filename = get_filename(dt,step);

    let url = format!("{}?dir=%2Fhrrr.{:04}{:02}{:02}%2F{}&file={}&{}", 
        OPT.hrrr_url.as_str(), 
        dt.year(), dt.month(), dt.day(),
        OPT.region.as_str(),
        filename.as_str(),
        STATIC_QUERY.as_str()
    );

    let mut pb = PathBuf::from(OPT.output_dir.as_str());
    pb.push(filename.as_str());
    let path = pb.as_path();
    let path_str = path.to_str().unwrap();

    if path.is_file() { // we already have it (from a previous run)
        info!("file {} already downloaded", filename);
        Ok(())

    } else { // we have to retrieve it from the NOAA server
        info!("downloading {}..", filename);
        debug!("from {}", url);

        if OPT.dry_run {
            Ok(()) // just simulate it
        } else {
            let mut file = tempfile::NamedTempFile::new()?; // don't use path yet as that would expose partial downloads to the world
            let mut response = reqwest::get(&url).await?;
            while let Some(chunk) = response.chunk().await? {
                file.write_all(&chunk)?;
            }

            if response.status() == http::StatusCode::OK {
                let file_len_kb = fs::metadata(file.path())?.len() / 1024;
                if file_len_kb > 0 {
                    std::fs::rename(file.path(), path); // now make it visible to the world as a permanent file
                    info!("{} kB saved to {}", file_len_kb, path_str);
                    Ok(())
                } else {
                    Err(anyhow!("empty file {}", path_str))
                }
            } else {
                Err(anyhow!("request failed with code {}", response.status().as_str()))
            }
            // note existing temp files will be automatically closed/deleted when dropped
        }
    }
}

/// account for slightly varying file schedule and availability
async fn download_file_with_retry (dt: &DateTime<Utc>, step: usize) -> Result<()> {
    let mut retry = 0;
    while retry < OPT.max_retry && download_file(dt, step).await.is_err() {
        info!("retry {}/{} in {} sec", retry, OPT.max_retry, OPT.retry_secs);
        sleep_secs(OPT.retry_secs).await;
        retry += 1;
    }

    if retry < OPT.max_retry {
        Ok(())
    } else {
        warn!("permanently failed to retrieve {} step {}", &fmt_date(dt), step);
        Err(anyhow!("max attempts for step exceeded"))
    }
}

/// download a forecast cycle from a given forecast start time 
async fn download_cycle (base: DateTime<Utc>, step0: usize, schedule: &Vec<u32>) {
    let len = schedule.len();
    let mut step = step0;

    info!("-- start download cycle {}: [{}..{}]", &fmt_date(&base), step0, schedule.len()-1);

    while step < len {
        wait_for_schedule( &base, schedule[step]).await; // will not wait if schedule has already passed
        download_file_with_retry( &base, step).await;
        step += 1;
    }

    info!("-- finished download cycle {}: [{}..{}]", &fmt_date(&base), step0, schedule.len()-1);
}

/// spawn tokio task to download cycle
fn spawn_download_cycle (base_dt: DateTime<Utc>, step0: usize, arc_schedule: sync::Arc<Vec<u32>>) {
    tokio::task::spawn( async move {
        download_cycle(base_dt, step0, &arc_schedule).await;
    });
}

async fn spawn_downloads (reg_schedule: &Vec<u32>, ext_schedule: &Vec<u32>) -> DateTime<Utc> {
    let arc_reg_schedule = sync::Arc::new(reg_schedule.to_vec());
    let arc_ext_schedule = sync::Arc::new(ext_schedule.to_vec());

    let select_schedule = |dt: &DateTime<Utc>| { if is_extended_forecast(dt) { ext_schedule } else { reg_schedule } };
    let select_arc = |dt: &DateTime<Utc>| { if is_extended_forecast(dt) { &arc_ext_schedule } else { &arc_reg_schedule } };

    let now = chrono::offset::Utc::now();

    // get last fully covered cycle (base1)
    let mut base1 = full_hour_dt(&now);
    while (now - base1).num_minutes() < *select_schedule(&base1).last().unwrap() as i64 { base1 -= hours(1); }

    // get current cycle (from where we start continuous download)
    let mut base0 = base1 + hours(1);
    let mut schedule0 = select_schedule(&base0);
    let d: u32 = (now - base0).num_minutes() as u32;
    let mut step1 = 0;
    while d >= schedule0[step1] { step1+= 1; }
    step1 += 1; // 0..step1 of previous will be covered by current cycle

    // if base1 is not an extended cycle, get most recent extended cycle
    let dh = base1.hour() % 6;
    if dh > 0 {
        let base6 = base1 - hours(dh);
        let step6 = select_schedule(&base1).len() as usize + dh as usize; // 0..step6 of extended cycle will be covered by previous cycle
        spawn_download_cycle(base6, step6, select_arc(&base6).clone()); // kick off download of most recent ext cycle
    }

    spawn_download_cycle(base1, step1, select_arc(&base1).clone()); // kick off download of last cycle

    // start continuous downloads of new cycles every hour
    loop {
        spawn_download_cycle(base0, 0, select_arc(&base0).clone());
        base0 += hours(1);
        sleep_secs(3600).await;
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let loglevel = if OPT.verbose {"info"} else {"warning"};
    env_logger::init_from_env( env_logger::Env::default().filter_or(env_logger::DEFAULT_FILTER_ENV, loglevel));

    if !check_output_dir() {
        Err(anyhow!("can't write output dir, aborting."))
    } else {
        if let Result::Ok((reg_schedule,ext_schedule)) = get_schedules(OPT.hrrr_dir_url.as_str()).await {
            if !OPT.schedule_only {
                spawn_downloads(&reg_schedule, &ext_schedule).await;
            }
            Ok(())
        } else {
            Err(anyhow!("can't run without schedule, aborting."))
        }
    }
}