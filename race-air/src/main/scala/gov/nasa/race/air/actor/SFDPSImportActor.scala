/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.air.translator.MessageCollectionParser
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.jms.TranslatingJMSImportActor
import javax.jms.Message

/**
  * specialized JMSImportActor for SWIM SFDPS MessageCollection XML messages
  * note that we can't hardwire the JMS config (authentication, URI, topic etc) since SWIM access might vary
  */
class SFDPSImportActor (config: Config) extends TranslatingJMSImportActor(config) {
  val flatten = config.getBooleanOrElse("flatten", true)
  val parser = new MessageCollectionParser

  parser.setFlightsReUsable(flatten) // if we flatten we don't have to make sure the collection is copied

  override protected def publishMessage (msg: Message): Unit = {
    val s = getContentSlice(msg)
    if (s.nonEmpty){
      val flights = parser.parseFlights(s)
      if (flights.nonEmpty) {
        if (flatten) {
          flights.foreach(publishFiltered)
        } else {
          publishFiltered(flights)
        }
      }
    }
  }
}
