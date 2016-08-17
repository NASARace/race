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

import gov.nasa.race.common.StringUtils
import org.joda.time.DateTime
import squants.motion.Velocity
import squants.space._
import squants.time._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.math._

package object data {

  //--- constants
  final val MeanEarthRadius = Kilometers(6371)
  final val NM = 1852.0 // length of Nautical Mile in meters
  final val TwoPi = 2 * Pi
  final val Length0 = Meters(0)
  final val Angle0 = Degrees(0)

  //--- WGS84 earth axes constants
  final val RE_E = 6378137.0000e+0
  final val RE_N = 6356752.3141e+0
  final val RE_FLATTENING = ( RE_E - RE_N ) / RE_E
  final val E_ECC = 2.0 * RE_FLATTENING - RE_FLATTENING * RE_FLATTENING


  //--- squantified math funcs
  @inline final def sin(α: Angle): Double = Math.sin(α.toRadians)
  @inline final def cos(α: Angle): Double = Math.cos(α.toRadians)
  //val cos = Math.cos(_)
  //val sin = Math.sin(_)
  @inline final def sin2(α: Angle) = { val a = Math.sin(α.toRadians); a * a }

  val √ = Math.sqrt(_)

  @inline final def square(d: Double) = d*d
  @inline final def normalize(α: Angle) = Degrees((α.toDegrees + 360.0) % 360)
  @inline final def normalize(d: Double) = (d + TwoPi) % TwoPi

  implicit def finiteDuration2TimeUnit(d: FiniteDuration): Time = Milliseconds(d.toMillis)

  implicit class RichDouble (val x: Double) extends AnyVal {
    @inline def ** (y: Double) = Math.pow(x,y)
  }

  //--- various abstractions of position objects
  trait Positionable {
    def position: LatLonPos
  }
  trait AltitudePositionable extends Positionable {
    def altitude: Length
  }
  trait DatedAltitudePositionable extends AltitudePositionable {
    def date: DateTime
  }
  trait MovingPositionable extends AltitudePositionable {
    def heading: Angle
    def speed: Velocity
  }

  trait IdentifiableAircraft {
    def flightId: String
    def cs: String
  }
  trait InFlightAircraft extends IdentifiableAircraft with DatedAltitudePositionable with MovingPositionable {
    def toShortString = {
      val d = date
      val hh = d.getHourOfDay
      val mm = d.getMinuteOfHour
      val ss = d.getSecondOfMinute
      val id = StringUtils.capLength(cs)(8)
      f"$id%-7s $hh%02d:$mm%02d:$ss%02d ${altitude.toFeet.toInt}%6dft ${normalize(heading).toDegrees.toInt}%3d° ${speed.toKnots.toInt}%4dkn"
    }
  }

  /**
    * abstraction for trajectories that does not imply underlying representation, allowing for memory
    * optimized implementations
    */
  trait FlightPath {
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