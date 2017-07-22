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

import gov.nasa.race.geo.{DatedAltitudePositionable, MovingPositionable}
import gov.nasa.race.common._
import gov.nasa.race.util.StringUtils

/**
  * package `gov.nasa.race.air` contains data definitions, translators, filters, models and actors that are related
  * to airspace monitoring and simulation. It does *not* contain specific visualization support
  *
  * Note that we keep WorldWind based visualization in a separate module
  */
package object air {

  trait IdentifiablePositionable {
    def id: String // channel specific (track number, tail number etc.)
    def cs: String // call sign (cross-channel ID)
  }
  trait InFlightAircraft extends IdentifiablePositionable with DatedAltitudePositionable with MovingPositionable {
    def toShortString = {
      val d = date
      val hh = d.getHourOfDay
      val mm = d.getMinuteOfHour
      val ss = d.getSecondOfMinute
      val id = StringUtils.capLength(cs)(8)
      f"$id%-7s $hh%02d:$mm%02d:$ss%02d ${altitude.toFeet.toInt}%6dft ${heading.toNormalizedDegrees.toInt}%3d° ${speed.toKnots.toInt}%4dkn"
    }

    def flightLevel: Int = ((altitude.toFeet)/500).toInt * 5

    def stateString = {
      f"FL${flightLevel}%d ${heading.toDegrees.toInt}%03d° ${speed.toKnots.toInt}%dkn"
    }
  }

  /**
    * abstraction for trajectories that does not imply underlying representation, allowing for memory
    * optimized implementations
    */
  trait AbstractFlightPath {
    def capacity: Int
    def add (pos: DatedAltitudePositionable)

    /** low level iteration support that does not require temporary objects for FlightPath elements
      * The provided function takes 5 arguments:
      *   Int - path element index
      *   Double,Double - lat,lon in Degrees
      *   Double - alt in meters
      *   Long - epoch millis
      */
    def foreach(f: (Int,Double,Double,Double,Long) => Unit)
  }
}