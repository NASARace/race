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

package gov.nasa.race.geo

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.Filter
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.uom._

/**
  * common base type for 2D distance filters
  * note that we can't extend Filter[GeoPosition] here because DistanceFilter2D also implements Filter[T] with
  * a more general type parameter
  */
trait DistFilt2D {
  val center: GeoPosition
  val radius: Length

  val limitDeg = (radius.toNauticalMiles + 0.5) / 60.0 // pre-filter for lat/lon
  val latDeg = center.φ.toDegrees
  val lonDeg = center.λ.toDegrees

  def pass(pos: GeoPosition): Boolean = {
    val dlat = Math.abs(latDeg - pos.φ.toDegrees)
    val dlon = Math.abs(lonDeg - pos.λ.toDegrees)

    if (dlon > limitDeg || dlat > limitDeg) { // no need to calculate precise distance
      false
    } else {
      GreatCircle.distance(center, pos) < radius
    }
  }
}

/**
  * a ConfigurableFilter for Any args
  */
class DistanceFilter2D (val center: GeoPosition, val radius: Length, val config: Config=null)
                  extends ConfigurableFilter with DistFilt2D {

  def this (conf: Config) = this(GeoPosition.fromDegrees(conf.getDouble("lat"),conf.getDouble("lon")),
                                 conf.getLength("radius"),conf)

  def pass (o: Any): Boolean = {
    if (o != null) {
      o match {
        case obj: GeoPositioned => super.pass(obj.position)
        case pos: GeoPosition => super.pass(pos)
        case _ => false // don't know distance if we don't have a GeoPosition
      }
    } else false
  }


}

class PosDistanceFilter2D (val center: GeoPosition, val radius: Length) extends Filter[GeoPosition] with DistFilt2D {

  def this (conf: Config) = this(GeoPosition.fromDegrees(conf.getDouble("lat"), conf.getDouble("lon")),
                                 conf.getLength("radius"))
}