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

package gov.nasa.race.actors.routers

import com.typesafe.config.Config
import gov.nasa.race.core._
import gov.nasa.race.data.PrecipImage

import scala.collection.mutable

/**
  * a specialized content based router for ITWS PrecipImage objects, which re-publishes the object
  * based on its 'product' field value to corresponding  <write-to>/<product-type> channels
  */
class ITWSPrecipTypeRouter (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  val routes = mutable.Map.empty[Int,mutable.Set[String]]

  override def onInitializeRaceActor (raceContext: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(raceContext, actorConf)
    routes += 9849 -> writeTo.map(_ + "/9849")
    routes += 9850 -> writeTo.map(_ + "/9850")
    routes += 9905 -> writeTo.map(_ + "/9905")
  }

  override def handleMessage = {
    case BusEvent(readFrom, precipImage:PrecipImage, originator) =>
      routes.get(precipImage.product) match {
        case Some(channels) => channels.foreach(publish(_, precipImage))
        case None => info(s"not routing precip type ${precipImage.product}")
      }
  }
}