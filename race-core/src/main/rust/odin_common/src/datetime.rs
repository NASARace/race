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

use chrono::{DateTime,Utc,NaiveDate,NaiveTime,NaiveDateTime,Local,TimeZone};
use serde::{Serialize,Deserialize,Serializer,Deserializer};
use std::time::Duration;
use std::ffi::OsStr;
use parse_duration::parse;

/// return minutes since given given DateTime<Utc> (negative if in future)
pub fn elapsed_minutes_since (dt: &DateTime<Utc>) -> i64 {
    let now = chrono::offset::Utc::now();
    (now - *dt).num_minutes()
}

pub fn is_between_inclusive (dt: &DateTime<Utc>, dt_start: &DateTime<Utc>, dt_end: &DateTime<Utc>) -> bool {
    dt >= dt_start && dt <= dt_end
}

/// get a DateTime<Utc> from a NaiveDate that is supposed to be in Utc
pub fn naive_utc_date_to_utc_datetime (nd: NaiveDate) -> DateTime<Utc> {
    let nt = NaiveTime::from_hms_opt(0, 0, 0).unwrap(); // 00:00:00 can't fail
    let ndt = NaiveDateTime::new(nd,nt);

    //DateTime::from_utc(ndt, Utc)
    DateTime::from_naive_utc_and_offset(ndt,Utc)
}

pub fn naive_local_date_to_utc_datetime (nd: NaiveDate) -> Option<DateTime<Utc>> {
    let nt = NaiveTime::from_hms_opt(0, 0, 0).unwrap(); // 00:00:00 can't fail
    let ndt = NaiveDateTime::new(nd,nt);

    // yeah - this can actually fail if the timezone changed during respective period
    Local.from_local_datetime(&ndt).single().map(|ldt| ldt.with_timezone(&Utc))
}

//--- support for serde

pub fn ser_short_rfc3339<S: Serializer> (dt: &DateTime<Utc>, s: S) -> Result<S::Ok, S::Error>  {
    let dfm = format!("{}", dt.format("%Y-%m-%dT%H:%M:%S%Z"));
    s.serialize_str(&dfm)
}

pub fn deserialize_duration <'a,D>(deserializer: D) -> Result<Duration,D::Error>
    where D: Deserializer<'a>
{
    String::deserialize(deserializer).and_then( |string| {
        parse(string.as_str())
            .map_err( |e| serde::de::Error::custom(format!("{:?}",e)))
    })
}

pub fn serialize_duration<S: Serializer> (dur: &Duration, s: S) -> Result<S::Ok, S::Error>  {
    let dfm = format!("{:?}", dur);
    s.serialize_str(&dfm)
}

//--- support for structopt parsers

pub fn parse_utc_datetime_from_date (s: &OsStr) -> DateTime<Utc> {
    let nd = NaiveDate::parse_from_str(s.to_str().unwrap(), "%Y-%m-%d").unwrap();
    naive_local_date_to_utc_datetime(nd).unwrap()
}
