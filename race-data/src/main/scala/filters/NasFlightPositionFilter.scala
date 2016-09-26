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

package gov.nasa.race.data.filters

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.ConfigurableFilter
import gov.nasa.race.data.{GreatCircle, LatLonPos}
import squants.Length
import squants.space.{Degrees, NauticalMiles}

/**
 * filter for NasFlight messages with positions within a given area
 */
class NasFlightPositionFilter (val center: LatLonPos, val radius: Length, val config: Config=null) extends ConfigurableFilter {
  final val posRE =  "<pos>([-+.\\d]+) ([-+.\\d]+)</pos>".r

  def this (conf: Config) = this(LatLonPos(Degrees(conf.getDoubleOrElse("lat",0)),
                                             Degrees(conf.getDoubleOrElse("lon",0))),
                                   NauticalMiles(conf.getDoubleOrElse("radius-nm", 0)), conf)

  override def pass (o: Any): Boolean = {
    o match {
      case txt: String =>
        if (txt.indexOf("<ns5:NasFlight")>0) {
          posRE.findFirstIn(txt) match {
            case Some(posRE(msgLat,msgLon)) =>
              val msgPos = LatLonPos( Degrees(msgLat.toDouble), Degrees(msgLon.toDouble))
              val dist = GreatCircle.distance(center, msgPos)
              dist < radius
            case _ => false
          }
        } else false
      case _ => false
    }
  }
}
