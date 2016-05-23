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

package gov.nasa.race.actors.translators

import com.typesafe.config.Config
import gov.nasa.race.core.{BusEvent, SubscribingRaceActor, PublishingRaceActor, RaceActor}
import gov.nasa.race.data.{LatLonPos, FlightPos}
import org.joda.time.DateTime
import squants.motion.UsMilesPerHour
import squants.space.{Degrees,Feet}

import scala.xml.{NodeSeq, Text, XML}


class FlightsNear2FlightPos (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  val writeTo = config.getString("write-to")

  override def handleMessage = {
    case BusEvent(_,xml:String,_) if (xml.indexOf("<flightPositions>")>0) =>
      log.info(s"translating ${xml.substring(0,20)}..")
      translate(xml) foreach { publish(writeTo, _) }
  }

  def translate (s: String) = {
    val doc = XML.loadString(s)
    var list: List[FlightPos] = Nil

    for (fps <- doc \\ "flightPosition") {
      var id = "?"; var cs = ""; var hdg = 0d; var lon = 0d; var lat = 0d;
      var spd = 0d; var alt = 0d; var date: DateTime = null

      for (e <- fps \ "_"){
        e match {
          case <flightId>{Text(item)}</flightId>  => id = item
          case <callsign>{Text(item)}</callsign>  => cs = item
          case <heading>{Text(item)}</heading> => hdg = item.toDouble
          case <positions>{positions @ _*}</positions> =>
            for (pos <- positions) {
              for (item <- pos \ "_") {
                item match {
                  case <lon>{Text(item)}</lon> => lon = item.toDouble
                  case <lat>{Text(item)}</lat> => lat = item.toDouble
                  case <speedMph>{Text(item)}</speedMph> => spd = item.toDouble
                  case <altitudeFt>{Text(item)}</altitudeFt> => alt = item.toDouble
                  case <date>{Text(item)}</date> => date = DateTime.parse(item)
                  case _ => // don't care
                }
              }
            }
          case _ => // don't care
        }
      }
      val fpos = FlightPos(id,cs,LatLonPos(Degrees(lat),Degrees(lon)),Feet(alt),UsMilesPerHour(spd),Degrees(hdg),date)
      list = fpos :: list
    }

    list
  }

}
