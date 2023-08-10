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

/// does a str have mixed lower and upper case chars
pub fn is_mixed_case (s:&str) -> bool {
    let mut has_upper = false;
    let mut has_lower = false;

    for s in s.chars() {
        if s.is_uppercase() {
            if has_lower { return true; }
            has_upper = true;
        } else {
            if has_upper { return true; }
            has_lower = true;
        }
    }
    false
}

/// does a str start with an uppercase char that is followed by a lowercase char (if any)
pub fn is_capitalized(s: &str) -> bool {
    if s.len() > 1 {
        let mut it = s.chars();
        it.next().unwrap().is_uppercase() && it.next().unwrap().is_lowercase()
    } else if !s.is_empty() {
        s.chars().next().unwrap().is_uppercase()
    } else {
        false
    }
}

/// return a new String that turns the first char of a given ascii str into uppercase and the rest into lowercase
pub fn ascii_capitalize(s: &str) -> String {
    if s.len() > 1 {
        let mut is_first = true;
        let cs = s.chars();
        cs.map(|c| {
            if is_first {
                is_first = false;
                c.to_ascii_uppercase()
            } else {
                c.to_ascii_lowercase()
            }
        }).collect::<String>()

    } else if !s.is_empty() {
        s.to_uppercase()
    } else {
        String::new()
    }
}

/// turn a str with char separated values into a Vec<String>
pub fn parse_string_vec (s: &str, delim: char) -> Vec<String> {
    s.split(delim).map(|x| x.trim().to_string()).collect()
}

/// generic function to turn a str with char separated values into a Vec<T> where T: FromStr
pub fn parse_vec<T: FromStr> (s: &str, delim: char) -> Vec<T> where <T as FromStr>::Err: core::fmt::Debug {
    s.split(delim).map( |x| x.trim().parse::<T>().unwrap()).collect()
}
