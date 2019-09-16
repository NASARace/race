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

object XyzPos {
  val zeroXyzPos = XyzPos(Length.Length0, Length.Length0, Length.Length0)
  val undefinedXyzPos = XyzPos(Length.UndefinedLength,Length.UndefinedLength,Length.UndefinedLength)

  def fromMeters(x: Double, y: Double, z: Double) = XyzPos(Meters(x),Meters(y),Meters(z))
}

/**
  * cartesian coordinates (e.g. ECEF)
  */
case class XyzPos (x: Length, y: Length, z: Length) {
  override def toString = {
    f"XyzPos{x=${x.toMeters}%+.3fm,${y.toMeters}%+.3fm,${z.toMeters}%+.3fm"
  }

  @inline def =:= (other: XyzPos): Boolean = (x =:= other.x) && (y =:= other.y) && (z =:= other.z)
  @inline final def isDefined: Boolean = x.isDefined && y.isDefined && z.isDefined

  def distanceTo (other: XyzPos): Length = {
    Meters( Math.sqrt( squared(x.toMeters - other.x.toMeters) +
                       squared(y.toMeters - other.y.toMeters) +
                       squared(z.toMeters - other.z.toMeters)))
  }

  @inline final def xMeters: Double = x.toMeters
  @inline final def yMeters: Double = y.toMeters
  @inline final def zMeters: Double = z.toMeters

  @inline final def toMeters: (Double,Double,Double) = (x.toMeters, y.toMeters, z.toMeters)
}
