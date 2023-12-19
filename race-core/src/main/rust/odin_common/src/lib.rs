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
#![allow(unused,uncommon_codepoints)]

pub mod strings;
pub mod macros;
pub mod fs;
pub mod datetime;
pub mod angle;
pub mod geo;
pub mod sim_clock;

//pub mod config;

// syntactic sugar - this is just more readable
fn sin(x:f64) -> f64 { x.sin() }
fn cos(x:f64) -> f64 { x.cos() }
fn sinh(x:f64) -> f64 { x.sinh() }
fn cosh(x:f64) -> f64 { x.cosh() }
fn tan(x:f64) -> f64 { x.tan() }
fn asin(x:f64) -> f64 {x.asin() }
fn atan(x:f64) -> f64 { x.atan() }
fn atanh(x:f64) -> f64 { x.atanh() }
fn sqrt(x:f64) -> f64 { x.sqrt() }
