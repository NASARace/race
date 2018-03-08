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
import gov.nasa.race.common.RecordWriter
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, SubscribingRaceActor}
import gov.nasa.race.track.{TrackTerminationMessage, TrackedObject}

import scala.collection.mutable.{HashMap => MHashMap}


/**
  * actor that periodically stores sets of tracks as arrays of binary records in a memory mapped file.
  *
  * Note that the array is dense, i.e. we move records upon completion/drop of a track (only the last
  * record is moved, and new ones are added at the end)
  */
class TrackStoreActor (val config: Config) extends SubscribingRaceActor with ContinuousTimeRaceActor with PeriodicRaceActor {

  val indexMap = MHashMap.empty[String,Int]
  val writer: RecordWriter = createWriter

  var changed = false // anything to store on the next snapshot?

  protected def createWriter: RecordWriter = getConfigurable[RecordWriter]("writer")

  override def handleMessage = {
    case BusEvent(_,track:TrackedObject,_) => updateTrack(track)
    case BusEvent(_,tm:TrackTerminationMessage,_) => removeTrack(tm)
    case RaceTick => storeSnapshot
  }

  def updateTrack (track: TrackedObject) = {
    val cs = track.cs
    indexMap.get(cs) match {
      case Some(idx) =>
        if (track.isDroppedOrCompleted) {
          val lastIdx = indexMap.size-1
          changed |= (if (lastIdx == idx) writer.remove(idx) else writer.move(lastIdx,idx))
        } else {
          changed |= writer.set(idx, track)
        }

      case None => // new one
        if (!track.isDroppedOrCompleted) {
          val idx = indexMap.size
          indexMap += cs -> idx
          changed |= writer.set(idx, track)
        }
    }
  }

  def removeTrack (tm: TrackTerminationMessage) = {
    indexMap.get(tm.cs) match {
      case Some(idx) => changed |= writer.remove(idx)
      case None =>
    }

  }

  def storeSnapshot = {
    if (changed){
      changed = false
      writer.store
    }
  }
}




