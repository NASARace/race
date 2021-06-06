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

package gov.nasa.race.ww.air

import java.awt.Color

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{AirLocator, TfmTrack, TfmTracks}
import gov.nasa.race.core.BusEvent
import gov.nasa.race.ww.{Images, RaceViewer}
import gov.nasa.race.ww.track.TrackLayer

/**
  * a RaceViewerActor WWJ layer to display TFM track data
  */
class TfmTracksLayer (val raceViewer: RaceViewer, val config: Config) extends TrackLayer[TfmTrack] with AirLocator {

  override def defaultColor = Color.magenta
  override def defaultSymbolImg = Images.getPlaneImage(color)

  def processTrack (tfmTrack: TfmTrack): Unit = {
    getTrackEntry(tfmTrack) match {
      case Some(trackEntry) =>
        if (tfmTrack.nextPos.isEmpty) removeTrackEntry(trackEntry)  // completed
        else updateTrackEntry(trackEntry,tfmTrack)

      case None =>
        if (!tfmTrack.nextPos.isEmpty) addTrackEntry(tfmTrack) // only add if this wasn't the completed message
    }
  }

  def handleTfmTracksLayerMessage: Receive = {
    case BusEvent(_,tracks: TfmTracks,_) =>
      incUpdateCount
      tracks.foreach(processTrack)
    case BusEvent(_,track: TfmTrack,_) =>
      incUpdateCount
      processTrack(track)

  }

  override def handleMessage = handleTfmTracksLayerMessage orElse super.handleMessage
}