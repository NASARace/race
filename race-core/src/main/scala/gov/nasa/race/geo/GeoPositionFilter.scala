/*
 * Copyright (c) 2022, United States Government, as represented by the
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
import gov.nasa.race.Filter
import gov.nasa.race.config.ConfigUtils.ConfigWrapper

object GeoPositionFilter {

  def fromConfig (conf: Config): Option[GeoPositionFilter] = {
    if (conf.hasPaths("nw", "se")) {
      Some( new BoundingBoxGeoFilter(conf.getGeoPosition("nw"), conf.getGeoPosition("se")))

      //... and more to follow
    } else {
      None
    }
  }

  def fromOptionalConfig (conf: Config, key: String): Option[GeoPositionFilter] = {
    conf.getOptionalConfig(key).flatMap( fromConfig)
  }
}

object PassAllGeoFilter extends GeoPositionFilter {
  override def pass(pos: GeoPosition): Boolean = true
}

object PassNoneGeoFilter extends GeoPositionFilter {
  override def pass(pos: GeoPosition): Boolean = false
}

/**
  * something that can filter GeoPositions
  *
  * TODO - unify this with DistanceFilterX
  */
trait GeoPositionFilter extends Filter[GeoPosition]

class BoundingBoxGeoFilter (val nwBounds: GeoPosition, val seBounds: GeoPosition) extends GeoPositionFilter {
  override def pass(pos: GeoPosition): Boolean = {
    (pos.φ >= seBounds.φ) && (pos.φ <= nwBounds.φ) && (pos.λ >= nwBounds.λ) && (pos.λ <= seBounds.λ)
  }
}


// TODO - we need something efficient for a general convex polygon