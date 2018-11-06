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

package gov.nasa.race.air.filter

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.geo.{GreatCircle, GeoPosition}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom._

/**
  * filter for XML messages with <pos>lat lon</pos> elements
 */
class XmlPosFilter(val center: GeoPosition, val radius: Length, val config: Config=null) extends ConfigurableFilter {
  final val posRE =  "<pos>([-+.\\d]+) ([-+.\\d]+)</pos>".r

  def this (conf: Config) = this(GeoPosition(Degrees(conf.getDoubleOrElse("lat",0)),
                                             Degrees(conf.getDoubleOrElse("lon",0))),
                                   NauticalMiles(conf.getDoubleOrElse("radius-nm", 0)), conf)

  override def pass (o: Any): Boolean = {
    o match {
      case txt: String =>
        posRE.findAllMatchIn(txt).exists( m => {
          val msgLat = m.group(1)
          val msgLon = m.group(2)
          val msgPos = GeoPosition(Degrees(msgLat.toDouble), Degrees(msgLon.toDouble))
          val dist = GreatCircle.distance(center, msgPos)
          dist < radius
        })

      case _ => false
    }
  }
}
