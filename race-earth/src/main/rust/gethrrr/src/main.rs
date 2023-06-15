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

 /// program to periodically retrieve HRRR weather reports 

use std::str::FromStr;
use structopt::StructOpt;
use std::fmt::{Write,Display};
use chrono::{
    Duration,
    DateTime,
    Datelike,
    Timelike,
};
use reqwest;
use std::io;
use regex::Regex;
//use thiserror::Error;
use anyhow::{anyhow,Context,Result,Ok};

#[macro_use]
extern crate lazy_static;

/*
#[derive(Error, Debug)]
pub enum MyError {
    #[error("HTTP Request failed: {}", .0)]
    ReqwestError(#[from] reqwest::Error),

    #[error("directory parse error: {0}")]
    DirParseError(String),
}
*/

/// command line argument structure
#[derive(StructOpt,Clone,Debug)]
struct Opt {

    /// url of server for filtered HRRR forecast files
    #[structopt(long,default_value="http://nomads.ncep.noaa.gov/cgi-bin/filter_hrrr_2d.pl")]
    hrrr_url: String,

    /// url pattern for directory listing of available forecast files
    #[structopt(long,default_value="http://nomads.ncep.noaa.gov/pub/data/nccf/com/hrrr/prod/hrrr.${yyyyMMdd}/conus/")]
    hrrr_list_url: String, 

    /// optional comma separated list of (hourly) minutes to download HRRR reports (overrides getting schedule from hrrr_list_url) 
    #[structopt(long,use_delimiter=true)]
    schedule: Vec<usize>,

    /// directory to store downloaded HRRR forecast files
    #[structopt(long,default_value="hrrr")]
    wx_path: String,

    /// comma separated list of grid field names to retrieve
    #[structopt(long,default_value="TCDC,TMP,UGRD,VGRD",use_delimiter=true)]
    fields: Vec<String>,

    /// comma separated list of grid levels to retrieve
    #[structopt(long,default_value="lev_2_m_above_ground,lev_10_m_above_ground,lev_entire_atmosphere",use_delimiter=true)]
    levels: Vec<String>,

    /// max forecast step in hours (from base hour)
    #[structopt(long,default_value="18")]
    max_hours: usize,

    /// download delay in minutes, used to compute schedule (ignored if explicit schedule is given)
    #[structopt(long,default_value="2")]
    delay_minutes: usize,

    /// max age (in hours) after which report files are deleted. If 0 (default) they are never automatically deleted
    #[structopt(long,default_value="0")]
    max_age: usize,
}

lazy_static! {
    #[derive(Debug)]
    static ref OPT: Opt = Opt::from_args();
}

//--- arg utility funcs

/// stringify iterator for Display elements with given delimiter without per-element allocation
fn mk_string<T: Display> (it: std::slice::Iter<'_,T>, delim: &str) -> String {
    it.fold(String::new(), |mut acc:String, x:&T| {
        if !acc.is_empty() {acc.push_str(delim)}
        write!(acc,"{}", x);
        acc
    })
}

fn parse_string_vec (s: &str, delim: char) -> Vec<String> {
    s.split(delim).map(|x| x.trim().to_string()).collect()
}

fn parse_vec<T: FromStr> (s: &str, delim: char) -> Vec<T> where <T as FromStr>::Err: core::fmt::Debug {
    s.split(delim).map( |x| x.trim().parse::<T>().unwrap()).collect()
}

/// for each time step compute average hourly minute of grib2 file availability on NOAA server by looking at directory web page
fn build_schedule (url_template: &str) -> anyhow::Result<Vec<usize>> {
    // watch out - the HTML format for HRRR dir listings might change
    let re = Regex::new(r#"\.grib2">hrrr\.t(\d{2})z.wrfsfcf(\d{2}).grib2</a>\s+(\d+)-(.+)-(\d{4})\s+(\d{2}):(\d{2})\s"#).unwrap();

    let now = chrono::offset::Utc::now();
    let dt = if now.hour() < 8 { now - Duration::hours((now.hour() + 1).into()) } else { now };

    let date_spec = format!("{:04}{:02}{:02}", dt.year(), dt.month(), dt.day());
    let url = url_template.replace( "${yyyyMMdd}", date_spec.as_str());

    let txt: String = reqwest::blocking::get(&url).with_context(|| format!("get {} failed", url))?.text()?;

    let mut schedule: Vec<usize> = Vec::new();

    for cap in re.captures_iter(txt.as_str()) {
        if cap.len() == 8 {
            // regex makes sure those are valid numbers, cap[0] is whole match
            let bh: usize = cap[1].parse().unwrap();  // base hour (number of data points for fch)
            let fch: usize = cap[2].parse().unwrap(); // forecast hour
            let m: usize = cap[7].parse().unwrap(); // file creation minute

            if fch <= OPT.max_hours {
                //print!("{:02} + {:2} = {:02}\t\t{}\n", bh, fch, m, &cap[0] );

                if fch >= schedule.len() { // first one for this forecast hour
                    schedule.resize(fch+1, 0);
                }

                let s = schedule[fch];
                if m > s {
                    if m - s > 30 { // m..0..s 
                        let d = (60-m + s)/(bh+1);
                        schedule[fch] = (60 + s - d) % 60;
                    } else { // s..m
                        schedule[fch] = s + (m-s)/(bh+1);
                    }
                } else if m < s {
                    if s - m > 30 { // s..0..m
                        let d = (60-s + m)/(bh+1);
                        schedule[fch] = (s + d) % 60;
                    } else {  // m..s
                        schedule[fch] = s + (s-m)/(bh+1);
                    }
                }
                // if m == s schedule[fch] does not change
            }
        }  
    }

    if schedule.is_empty() { 
        Err(anyhow!("unexpected directory content"))
    } else {
        for i in 0..schedule.len() {
            schedule[i] = (schedule[i] + OPT.delay_minutes) % 60;
        }
        Ok(schedule)
    }
}

fn download_hrrr (schedule: Vec<usize>) {
    
}

fn main() {
    let res = build_schedule(OPT.hrrr_list_url.as_str());
    print!("{:?}\n", res);
}