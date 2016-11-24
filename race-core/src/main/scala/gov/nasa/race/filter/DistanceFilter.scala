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

package gov.nasa.race.filter

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.geo.{GreatCircle, LatLonPos, Positionable}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._

/**
  * a distance based filter for Positionables
  */
class DistanceFilter (val center: LatLonPos, val radius: Length, val config: Config=null) extends ConfigurableFilter {

  def this (conf: Config) = this(LatLonPos(Degrees(conf.getDouble("lat")),Degrees(conf.getDouble("lon"))),
                                 NauticalMiles(conf.getDouble("radius-nm")),conf)

  val limitDeg = (radius.toNauticalMiles + 0.5) / 60.0 // pre-filter for lat/lon
  val latDeg = center.φ.toDegrees
  val lonDeg = center.λ.toDegrees

  override def pass (o: Any): Boolean = {
    if (o != null) {
      o match {
        case obj: Positionable =>
          val pos = obj.position
          val dlat = Math.abs(latDeg - pos.φ.toDegrees)
          val dlon = Math.abs(lonDeg - pos.λ.toDegrees)

          if (dlon > limitDeg || dlat > limitDeg) { // no need to calculate precise distance
            false
          } else {
            GreatCircle.distance(center, pos) < radius
          }
        case other => false // don't know distance if its not a Positionable
      }
    } else false
  }
}
