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

use std::fmt;
use std::fmt::{Display,Write};
use std::str::FromStr;

/// stringify iterator for Display elements with given delimiter without per-element allocation
pub fn mk_string<T: Display> (it: std::slice::Iter<'_,T>, delim: &str) -> Result<String,fmt::Error> {
    let mut s = String::new();

    for e in it {
        if !s.is_empty() { s.push_str(delim); }
        write!(s,"{}",e)?
    }
    Ok(s)
}

/// turn a str with char separated values into a Vec<String>
pub fn parse_string_vec (s: &str, delim: char) -> Vec<String> {
    s.split(delim).map(|x| x.trim().to_string()).collect()
}

/// generic function to turn a str with char separated values into a Vec<T> where T: FromStr
pub fn parse_vec<T: FromStr> (s: &str, delim: char) -> Vec<T> where <T as FromStr>::Err: core::fmt::Debug {
    s.split(delim).map( |x| x.trim().parse::<T>().unwrap()).collect()
}