/*
 * Copyright (c) 2016, United States Government, as represented by the
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
package gov.nasa.race

import gov.nasa.race.uom.Angle.{Degrees, Radians}
import gov.nasa.race.uom.Length.{Feet, Kilometers, Meters, NauticalMiles, UsMiles}
import gov.nasa.race.uom.Time.{Days, Hours, Minutes, Seconds}

/**
  * package units of measure provides value class abstractions for double quantities that
  * represent physical units.
  *
  * The reasons for not using the venerable squants library are that we need zero cost abstraction via
  * Scala value classes (unit types only exist at compile time), and we need to avoid dependencies on 3rd
  * party code that is not constantly updated/maintained (e.g. for Scala 2.12)
  *
  * Note that package uom follows the approach of using base units in its underlying representation, i.e. we do
  * not store units separately
  *
  * We also do not factor out common operators into universal traits. While this would give us unit-homogeneous
  * operators without duplicated code, it also would come at the cost of allocation
  */
package object uom {

  // postfix dimensions
  implicit class RichUomDouble (val d: Double) extends AnyVal {

    //--- angle
    @inline def deg: Angle = Degrees(d)
    @inline def `°`: Angle = Degrees(d)
    @inline def rad: Angle = Radians(d)

    //--- length
    @inline def ft: Length = Feet(d)
    @inline def m: Length = Meters(d)
    @inline def mi: Length = UsMiles(d)
    @inline def km: Length = Kilometers(d)
    @inline def nm: Length = NauticalMiles(d)
  }

  implicit class RichUomLong (val l: Long) extends AnyVal {
    @inline def sec: Time = Seconds(l)
    @inline def _min: Time = Minutes(l)
    @inline def hrs: Time = Hours(l)
    @inline def days: Time = Days(l)
  }

  //--- used to avoid disambituities due to type erasure
  implicit object AngleDisambiguator
  implicit object LengthDisambiguator
  implicit object SpeedDisambiguator
  implicit object AreaDisambiguator
  implicit object TemperatureDisambiguator
  implicit object PowerDisambiguator
}
