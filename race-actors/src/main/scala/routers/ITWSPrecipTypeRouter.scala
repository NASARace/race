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

/**
  * a specialized content based router for ITWS precip messages, which
  * gets the product type from the message and re-published the message
  * to a corresponding channel <write-to-base>/<product-type>
  */
class ITWSPrecipTypeRouter (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  val writeToBase = config.getString("write-to-base")

  // the standard ITWS types
  val writeToChannels = Map (9849 -> (writeToBase + "9849"), 9850 -> (writeToBase + "9850"), 9905 -> (writeToBase + "9905"))

  override def handleMessage = {
    case BusEvent(readFrom, precipImage:PrecipImage, originator) =>
      writeToChannels.get(precipImage.product) match {
        case Some(writeTo) => publish(writeTo, precipImage)
        case None => info(s"$name not routing precip type ${precipImage.product}")
      }
  }
}