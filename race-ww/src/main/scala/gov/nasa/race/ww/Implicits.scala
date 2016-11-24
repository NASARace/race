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

package gov.nasa.race.ww

import gov.nasa.race.geo.{AltitudePositionable, LatLonPos}
import gov.nasa.worldwind.geom.{LatLon, Position, Angle => WWAngle}
import gov.nasa.race.uom._

import scala.language.implicitConversions


/**
  * implicit defs for RACE <-> WorldWind conversions
  */
object Implicits {

  implicit def toWWPosition (p: AltitudePositionable): Position = wwPosition(p.position, p.altitude)

  implicit def toWWPosition (pos: LatLonPos): Position = Position.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def angleToWWAngle (angle: Angle): WWAngle = WWAngle.fromDegrees(angle.toDegrees)

  implicit def latLonPos2LatLon (pos: LatLonPos): LatLon = LatLon.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def LatLon2LatLonPos (latLon: LatLon): LatLonPos = LatLonPos.fromDegrees( latLon.latitude.degrees, latLon.longitude.degrees)

}
