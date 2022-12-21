/*
 * Copyright (c) 2018, United States Government, as represented by the
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

package gov.nasa.race.geo

import gov.nasa.race.common._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._

import scala.math.sqrt

object XyzPos {
  val zeroXyzPos = XyzPos(Length.Length0, Length.Length0, Length.Length0)
  val undefinedXyzPos = XyzPos(Length.UndefinedLength,Length.UndefinedLength,Length.UndefinedLength)

  def fromMeters(x: Double, y: Double, z: Double): XyzPos = XyzPos(Meters(x),Meters(y),Meters(z))
  def fromMeters(p: Xyz): XyzPos = fromMeters(p.x, p.y, p.z)
}

trait Cartesian3Pos {
  def x: Length
  def y: Length
  def z: Length

  @inline def =:= (other: XyzPos): Boolean = (x =:= other.x) && (y =:= other.y) && (z =:= other.z)
  @inline final def isDefined: Boolean = x.isDefined && y.isDefined && z.isDefined

  def distanceTo (other: Cartesian3Pos): Length = {
    Meters( Math.sqrt( squared(x.toMeters - other.x.toMeters) +
                       squared(y.toMeters - other.y.toMeters) +
                       squared(z.toMeters - other.z.toMeters)))
  }

  @inline final def xMeters: Double = x.toMeters
  @inline final def yMeters: Double = y.toMeters
  @inline final def zMeters: Double = z.toMeters

  @inline final def toMeters: (Double,Double,Double) = (x.toMeters, y.toMeters, z.toMeters)

  @inline final def toXyz: Xyz = Xyz(x.toMeters, y.toMeters, z.toMeters)

  // we keep these dimension-less since they are often just used for scaling positions
  @inline final def length2: Double = (x.toMeters)*(x.toMeters) + (y.toMeters)*(y.toMeters) + (z.toMeters)*(z.toMeters)
  @inline final def length: Double = sqrt(length2)

  @inline final def * (s: Double): XyzPos = XyzPos( x*s, y*s, z*s)
  @inline final def / (s: Double): XyzPos = XyzPos( x/s, y/s, z/s)

  override def toString = {
    f"XyzPos{x=${x.toMeters}%+.3fm,${y.toMeters}%+.3fm,${z.toMeters}%+.3fm"
  }
}

/**
  * cartesian coordinates (e.g. ECEF)
  */
case class XyzPos (x: Length, y: Length, z: Length) extends Cartesian3Pos


case class MutXyzPos (var x: Length = Length0, var y: Length = Length0, var z: Length = Length0) extends Cartesian3Pos {
  def set (newX: Length, newY: Length, newZ: Length): Unit = {
    x = newX
    y = newY
    z = newZ
  }

  def setFromMeters (xm: Double, ym: Double, zm: Double): Unit = {
    x = Meters(xm)
    y = Meters(ym)
    z = Meters(zm)
  }
}
