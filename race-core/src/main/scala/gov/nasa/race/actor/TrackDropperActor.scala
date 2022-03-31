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

package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.SubscribingRaceActor
import gov.nasa.race.track.{TrackCompleted, TrackCsChanged, TrackDropped, Tracked3dObject, TrackedObject, TrackedObjects}

import scala.collection.mutable

/**
  * actor that publishes FlightDropped messages for FlightPos objects which haven't been
  * updated for a configurable amount of (sim-)time
  *
  * nothing we have to add here, it's all in FPosDropper, but we might move the flights map
  * into the concrete class at some point
  */
class TrackDropperActor(val config: Config) extends SubscribingRaceActor with TrackDropper {
  val tracks = mutable.HashMap.empty[String,TrackedObject]

  override def removeStaleTrack(ac: TrackedObject) = tracks -= ac.cs

  def update (track: TrackedObject): Unit = {
    if (track.isDroppedOrCompleted) {  // ? do we still need to publish TrackDropped event if the track has a drop status?
      tracks -= track.cs
    } else {
      tracks += track.cs -> track
    }
  }

  override def handleMessage = handleFPosDropperMessage orElse {
    case BusEvent(_,track: TrackedObject,_) => update(track)
    case BusEvent(_,tracks:TrackedObjects[_],_) => tracks.foreach(update)

    case BusEvent(_,fcompleted: TrackCompleted,_) => tracks -= fcompleted.cs
    case BusEvent(_,_:TrackDropped,_) => // ignore - we are generating these

    case BusEvent(_,_:TrackCsChanged,_) => // ignore
  }
}
