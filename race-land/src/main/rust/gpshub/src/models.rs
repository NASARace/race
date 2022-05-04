/*
 * Copyright (c) 2022, United States Government, as represented by the
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

use serde::{Deserialize, Serialize};
use std::fmt;

/// data packet we receive from devices as JSON text
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct Gps {

  //--- mandatory fields
  /// device id code
  pub id: u64,

  /// position date as epoch millis
  pub date: u64,

  /// latitude in degrees
  pub lat: f64,

  /// longitude in degrees
  pub lon: f64,

  /// altitude in meters
  pub alt: f64,

  //--- optional fields

  /// accuracy in meters (optional)
  #[serde(default="default_undefined")]
  pub acc: f64,

  /// headings in degrees (optional)
  #[serde(default="default_undefined")]
  pub hdg: f64,

  /// speed in meters/sec (optional)
  #[serde(default="default_undefined")]
  pub spd: f64,

  /// distance traveled in meters (optional)
  #[serde(default="default_undefined")]
  pub dist: f64,

  /// organization code (optional)
  #[serde(default="default_zero")]
  pub org: u32,

  /// role code (optional)
  #[serde(default="default_zero")]
  pub role: u32,

  /// status code (optional)
  #[serde(default="default_zero")]
  pub status: u32,
}

fn default_undefined() -> f64 {
  f64::NAN
}

fn default_zero() -> u32 {
  0
}

fn display_f64 (v: f64, prec: usize)-> String {
  if v.is_nan() {
    String::from("null")
  } else {
    format!("{:.prec$}", v)
  }
} 

fn csv_f64 (v: f64, prec: usize)-> String {
  if v.is_nan() {
    String::new()
  } else {
    format!("{:.prec$}", v)
  }
}

impl Gps {

  /// serialize to canonical GPS position CSV format
  pub fn to_csv(&self) -> String {
    let epoch_millis = self.date * 1000; // we get the timestamp as epoch seconds but report in msec

    let mut s = format!("\"{}\"", self.id.to_string());  // report this as a string
    s.push_str(","); s.push_str( &epoch_millis.to_string());
    s.push_str(","); s.push_str( &csv_f64(self.lat,6));
    s.push_str(","); s.push_str( &csv_f64(self.lon,6));
    s.push_str(","); s.push_str( &csv_f64(self.alt,1));
    s.push_str(","); s.push_str( &csv_f64(self.acc, 1));
    s.push_str(","); s.push_str( &csv_f64(self.hdg, 0));
    s.push_str(","); s.push_str( &csv_f64(self.spd, 2));
    s.push_str(","); s.push_str( &csv_f64(self.dist, 0));
    s.push_str(","); s.push_str( &self.org.to_string());
    s.push_str(","); s.push_str( &self.role.to_string());
    s.push_str(","); s.push_str( &self.status.to_string());
    s
  }

  /// add the provided timestamp this gps record was received
  pub fn to_recv_csv(&self,dtg_recv: u64) -> String {
    let mut s = dtg_recv.to_string();
    s.push_str(",");
    s.push_str(&self.to_csv());
    s
  }
}


/// output formatting for Gps records using JSON
impl fmt::Display for Gps {
  fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
    write!(f, "{{\"id\":{},\"date\":{},\"lat\":{:.6},\"lon\":{:.6},\"alt\":{:.1},\"acc\":{},\"hdg\":{},\"spd\":{},\"dist\":{},\"org\":{},\"role\":{},\"status\":{}}}", 
          self.id, self.date, self.lat, self.lon, self.alt, 
          display_f64(self.acc, 1),
          display_f64(self.hdg, 0),
          display_f64(self.spd, 2),
          display_f64(self.dist, 0),
          self.org, self.role, self.status)
  }
}

