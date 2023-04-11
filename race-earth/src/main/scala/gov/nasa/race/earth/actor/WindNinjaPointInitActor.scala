/*
 * Copyright (c) 2023, United States Government, as represented by the
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
package gov.nasa.race.earth.actor

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.earth.{RawsParser, WxStationAvailable}

/**
 * actor that listens for WxStationAvailable messages and bundles them into WindNinja station init files of the form:
 *
 *   "Station_Name","Coord_Sys(PROJCS,GEOGCS)","Datum(WGS84,NAD83,NAD27)","Lat/YCoord","Lon/XCoord","Height","Height_Units(meters,feet)","Speed","Speed_Units(mph,kph,mps)","Direction(degrees)","Temperature","Temperature_Units(F,C)","Cloud_Cover(%)","Radius_of_Influence","Radius_of_Influence_Units(miles,feet,meters,km)"
 *   "BNDC","GEOGCS","WGS84",37.130940,-122.172610,2598,"feet",10,"mph",270,70,"F",0,-1,"miles"
 */
class WindNinjaPointInitActor(val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  val parser = new RawsParser

  override def handleMessage: Receive = handlePointInitMessage orElse super.handleMessage

  def handlePointInitMessage: Receive = {
    case WxStationAvailable(wx,file,date) =>
  }


}
