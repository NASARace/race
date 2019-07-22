/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.uom

import gov.nasa.race.MaybeUndefined

import scala.concurrent.duration.FiniteDuration

/**
  * acceleration quantitites
  * basis is m/s²
  */
object Acceleration {
  final val MetersPerSec2inKnotsPerMin = 21600.0 / 1852.0

  final val Acceleration0 = new Acceleration(0)
  final val UndefinedAcceleration = new Acceleration(Double.NaN)
  final implicit val εAcceleration = MetersPerSecond2(1e-10)


  @inline def isDefined(x: Acceleration): Boolean = !x.d.isNaN

  //--- constructors
  @inline def MetersPerSecond2(d: Double) = new Acceleration(d)
  @inline def KnotsPerMinute(d: Double) = new Acceleration(d * MetersPerSec2inKnotsPerMin)

}

class Acceleration protected[uom] (val d: Double) extends AnyVal
                                                   with Ordered[Acceleration] with MaybeUndefined {
  import Acceleration._

  def toMetersPerSecond2: Double = d
  def toKnotsPerMinute: Double = d / MetersPerSec2inKnotsPerMin

  @inline def + (x: Acceleration): Acceleration = new Acceleration(d + x.d)
  @inline def - (x: Acceleration): Acceleration = new Acceleration(d - x.d)

  @inline def * (x: Double): Acceleration = new Acceleration(d * x)
  @inline def / (x: Double): Acceleration = new Acceleration(d / x)

  @inline def * (dur: FiniteDuration) = new Speed((d * dur.toMicros)/1e6)

  @inline def ≈ (x: Acceleration)(implicit εAcceleration: Acceleration) = Math.abs(d - x.d) <= εAcceleration.d
  @inline def ~= (x: Acceleration)(implicit εAcceleration: Acceleration) = Math.abs(d - x.d) <= εAcceleration.d

  @inline def =:= (x: Acceleration) = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Acceleration) = d == x.d

  @inline override def < (x: Acceleration): Boolean = d < x.d
  @inline override def <= (x: Acceleration): Boolean = d <= x.d
  @inline override def > (x: Acceleration): Boolean = d > x.d
  @inline override def >= (x: Acceleration): Boolean = d >= x.d
  @inline override def compare (other: Acceleration): Int = if (d > other.d) 1 else if (d < other.d) -1 else 0
  @inline override def compareTo (other: Acceleration): Int = compare(other)

  //-- undefined value handling (value based alternative for finite cases that would otherwise require Option)
  @inline override def isUndefined = d.isNaN
  @inline override def isDefined = !d.isNaN

  override def toString = show
  def show = s"${d}m/s²"
  def showMetersPerSecond2 = show
  def showKnotsPerMinute = s"${toKnotsPerMinute}%.3fkn/min"
}
