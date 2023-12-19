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

use std::{ops,cmp,fmt};

pub fn canonicalize_90 (d:f64) -> f64 {
    let mut x = d % 360.0;
    if x < 0.0 { x = 360.0 + x } // normalize to 0..360

    if x > 270.0 { x - 360.0}
    else if x > 90.0 { 180.0 - x }
    else { x }
}

pub fn canonicalize_180 (d: f64) -> f64 {
    let mut x = d % 360.0;
    if x < 0.0 { x = 360.0 + x } // normalize to 0..360

    if x > 180.0 { x - 360.0 } else { x }
}

pub fn canonicalize_360 (d: f64) -> f64 {
    let x = d % 360.0;
    if x < 0.0 { 360.0 + x } else { x }
}

//--- Angle

/// abstraction for angles [0..360]
#[derive(Debug,Clone,Copy)]
pub struct Angle(f64);

impl Angle {
    pub fn from_degrees(deg: f64) -> Angle { Angle(canonicalize_360(deg)) }
    pub fn from_radians(rad: f64) -> Angle { Angle::from_degrees(rad.to_degrees()) }

    pub fn sin(&self) ->f64 { self.0.to_radians().sin() }
    pub fn sinh(&self) ->f64 { self.0.to_radians().sinh() }
    pub fn asin(&self) ->f64 { self.0.to_radians().asin() }
    pub fn cos(&self) ->f64 { self.0.to_radians().cos() }
    pub fn cosh(&self) ->f64 { self.0.to_radians().cosh() }
    pub fn acos(&self) ->f64 { self.0.to_radians().acos() }
    pub fn tan(&self) ->f64 { self.0.to_radians().tan() }
    pub fn tanh(&self) ->f64 { self.0.to_radians().tanh() }
    pub fn atan(&self) ->f64 { self.0.to_radians().atan() }

    //... and many more

    pub fn degrees(&self) -> f64 { self.0 }
    pub fn radians(&self) -> f64 { self.0.to_radians() }

    pub fn canonicalize(&self) -> f64 { canonicalize_360(self.0) }
}

impl fmt::Display for Angle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}°", self.0)
    }
}

impl ops::Add<Angle> for Angle {
    type Output = Self;

    fn add (self,rhs:Angle) -> Angle {
        Angle(canonicalize_360(self.0 + rhs.0))
    }
}

impl ops::Sub<Angle> for Angle {
    type Output = Self;

    fn sub (self,rhs:Angle) -> Angle {
        Angle(canonicalize_360((self.0 - rhs.0)))
    }
}

impl ops::Mul<f64> for Angle {
    type Output = Self;

    fn mul (self,rhs:f64) -> Angle {
        Angle(canonicalize_360(self.0 * rhs))
    }
}

impl ops::Div<f64> for Angle {
    type Output = Self;

    fn div (self,rhs:f64) -> Angle {
        Angle(canonicalize_360(self.0 / rhs))
    }
}

impl cmp::Ord for Angle {
    fn cmp(&self, other: &Self) -> cmp::Ordering {
        if self.0 < other.0 { cmp::Ordering::Less }
        else if self.0 == other.0 { cmp::Ordering::Equal }
        else { cmp::Ordering::Greater }
    }
}

impl cmp::PartialOrd for Angle {
    fn partial_cmp(&self,other:&Self) -> Option<cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl cmp::Eq for Angle {}

impl cmp::PartialEq for Angle {
    fn eq(&self, other: &Self) -> bool { self.0 == other.0 }
}

//--- LatAngle

/// Angle with value bounds [-90..90]
#[derive(Debug,Clone,Copy)]
pub struct LatAngle(f64);

impl LatAngle {
    pub fn from_degrees(deg: f64) -> LatAngle {
        LatAngle(canonicalize_90(deg))
    }
    pub fn from_radians(rad: f64) -> LatAngle { LatAngle::from_degrees(rad.to_degrees()) }

    pub fn sin(&self) ->f64 { self.0.to_radians().sin() }
    pub fn sinh(&self) ->f64 { self.0.to_radians().sinh() }
    pub fn asin(&self) ->f64 { self.0.to_radians().asin() }
    pub fn cos(&self) ->f64 { self.0.to_radians().cos() }
    pub fn cosh(&self) ->f64 { self.0.to_radians().cosh() }
    pub fn acos(&self) ->f64 { self.0.to_radians().acos() }
    pub fn tan(&self) ->f64 { self.0.to_radians().tan() }
    pub fn tanh(&self) ->f64 { self.0.to_radians().tanh() }
    pub fn atan(&self) ->f64 { self.0.to_radians().atan() }
    //... and many more

    pub fn degrees(&self) -> f64 { self.0 }
    pub fn radians(&self) -> f64 { self.0.to_radians() }

    pub fn to_angle(&self) -> Angle { Angle(canonicalize_360(self.0)) }
    pub fn canonicalize(&self) -> f64 { canonicalize_90(self.0) }
}

impl fmt::Display for LatAngle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}°", self.0)
    }
}

impl ops::Add<LatAngle> for LatAngle {
    type Output = Self;

    fn add (self,rhs:LatAngle) -> LatAngle {
        LatAngle(canonicalize_90(self.0 + rhs.0))
    }
}

impl ops::Sub<LatAngle> for LatAngle {
    type Output = Self;

    fn sub (self,rhs:LatAngle) -> LatAngle {
        LatAngle(canonicalize_90((self.0 - rhs.0)))
    }
}

impl ops::Mul<f64> for LatAngle {
    type Output = Self;

    fn mul (self,rhs:f64) -> LatAngle {
        LatAngle(canonicalize_90(self.0 * rhs))
    }
}

impl ops::Div<f64> for LatAngle {
    type Output = Self;

    fn div (self,rhs:f64) -> LatAngle {
        LatAngle(canonicalize_90(self.0 / rhs))
    }
}

impl cmp::Ord for LatAngle {
    fn cmp(&self, other: &Self) -> cmp::Ordering {
        if self.0 < other.0 { cmp::Ordering::Less }
        else if self.0 == other.0 { cmp::Ordering::Equal }
        else { cmp::Ordering::Greater }
    }
}

impl cmp::PartialOrd for LatAngle {
    fn partial_cmp(&self,other:&Self) -> Option<cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl cmp::Eq for LatAngle {}

impl cmp::PartialEq for LatAngle {
    fn eq(&self, other: &Self) -> bool { self.0 == other.0 }
}

//--- LonAngle

/// abstraction for angles [-180..180]
#[derive(Debug,Clone,Copy)]
pub struct LonAngle(f64);

impl LonAngle {
    pub fn from_degrees(deg: f64) -> LonAngle { LonAngle(canonicalize_180(deg)) }
    pub fn from_radians(rad: f64) -> LonAngle { LonAngle::from_degrees(rad.to_degrees()) }

    pub fn sin(&self) ->f64 { self.0.to_radians().sin() }
    pub fn sinh(&self) ->f64 { self.0.to_radians().sinh() }
    pub fn asin(&self) ->f64 { self.0.to_radians().asin() }
    pub fn cos(&self) ->f64 { self.0.to_radians().cos() }
    pub fn cosh(&self) ->f64 { self.0.to_radians().cosh() }
    pub fn acos(&self) ->f64 { self.0.to_radians().acos() }
    pub fn tan(&self) ->f64 { self.0.to_radians().tan() }
    pub fn tanh(&self) ->f64 { self.0.to_radians().tanh() }
    pub fn atan(&self) ->f64 { self.0.to_radians().atan() }
    //... and many more

    pub fn degrees(&self) -> f64 { self.0 }
    pub fn radians(&self) -> f64 { self.0.to_radians() }

    pub fn to_angle(&self) -> Angle { Angle(canonicalize_360(self.0)) }
    pub fn canonicalize(&self) -> f64 { canonicalize_180(self.0) }
}

impl fmt::Display for LonAngle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}°", self.0)
    }
}

impl ops::Add<LonAngle> for LonAngle {
    type Output = Self;

    fn add (self,rhs:LonAngle) -> LonAngle {
        LonAngle(canonicalize_180(self.0 + rhs.0))
    }
}

impl ops::Sub<LonAngle> for LonAngle {
    type Output = Self;

    fn sub (self,rhs:LonAngle) -> LonAngle {
        LonAngle(canonicalize_180((self.0 - rhs.0)))
    }
}

impl ops::Mul<f64> for LonAngle {
    type Output = Self;

    fn mul (self,rhs:f64) -> LonAngle {
        LonAngle(canonicalize_180(self.0 * rhs))
    }
}

impl ops::Div<f64> for LonAngle {
    type Output = Self;

    fn div (self,rhs:f64) -> LonAngle {
        LonAngle(canonicalize_180(self.0 / rhs))
    }
}

impl cmp::Ord for LonAngle {
    fn cmp(&self, other: &Self) -> cmp::Ordering {
        if self.0 < other.0 { cmp::Ordering::Less }
        else if self.0 == other.0 { cmp::Ordering::Equal }
        else { cmp::Ordering::Greater }
    }
}

impl cmp::PartialOrd for LonAngle {
    fn partial_cmp(&self,other:&Self) -> Option<cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl cmp::Eq for LonAngle {}

impl cmp::PartialEq for LonAngle {
    fn eq(&self, other: &Self) -> bool { self.0 == other.0 }
}
