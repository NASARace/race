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

import gov.nasa.race.geo.{GeoPosition3D, LatLonPos}
import gov.nasa.race.track.TrackPoint3D
import gov.nasa.worldwind.geom.{LatLon, Position}
import gov.nasa.race.uom._

import scala.language.implicitConversions


/**
  * implicit defs for RACE <-> WorldWind conversions
  */
object Implicits {

  implicit def latLonPos2Position(pos: LatLonPos): Position = Position.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def geoPosition3d2Position (pos: GeoPosition3D): Position = {
    Position.fromDegrees(pos.position.φ.toDegrees, pos.position.λ.toDegrees, pos.altitude.toMeters)
  }

  implicit def trackPoint3D2Position(e: TrackPoint3D): Position = wwPosition(e.position, e.altitude)

  implicit def angle2WWAngle(angle: Angle): WWAngle = gov.nasa.worldwind.geom.Angle.fromDegrees(angle.toDegrees)

  implicit def latLonPos2LatLon (pos: LatLonPos): LatLon = LatLon.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def LatLon2LatLonPos (latLon: LatLon): LatLonPos = LatLonPos.fromDegrees( latLon.latitude.degrees, latLon.longitude.degrees)

}
