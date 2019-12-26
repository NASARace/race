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
package gov.nasa.race.ww.air

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{ARTCC, SFDPSTrack, SFDPSTracks, TrackedAircraft}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.GeoPositioned
import gov.nasa.race.ww.{Images, RaceViewer}
import gov.nasa.race.ww.track.ModelTrackLayer

/**
  * a TrackLayer for SFDPS (en route) SWIM data
  */
class SFDPSTracksLayer (val raceViewer: RaceViewer, val config: Config) extends ModelTrackLayer[TrackedAircraft] {



  override def defaultSymbolImg = Images.getPlaneImage(color)
  override def getTrackKey(track: TrackedAircraft): String = track.cs
  override def queryLocation(id: String): Option[GeoPositioned] = ARTCC.artccs.get(id)

  def handleSFDPSMessage: Receive = {
    case BusEvent(_,track:SFDPSTrack,_) => handleTrack(track)
    case BusEvent(_,tracks:SFDPSTracks,_) =>
  }

  override def handleMessage = handleSFDPSMessage orElse super.handleMessage
}
