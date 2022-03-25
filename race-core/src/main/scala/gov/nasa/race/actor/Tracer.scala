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
package gov.nasa.race.actor

import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.SubscribingRaceActor
import gov.nasa.race.track.{TrackTerminationMessage, Tracked3dObject}
import gov.nasa.race.trajectory.{CompressedTrace, MutTrajectory}

import scala.collection.mutable.{HashMap => MutHashMap}

class TracerEntry (var obj: Tracked3dObject, val trajectory: MutTrajectory)

/**
  * base type for actors that create and process trajectory traces for TrackedObject input
  *
  * this actor is per default configured as a pass-through actor, i.e. it (possibly filtered)
  * re-publishes the track messages it receives on its configured write-to channel
  *
  * we only keep trajectories and re-publish for tracks that pass our filter(s)
  *
  * note that implementors can be used in a circular way by also processing messages that are
  * created by their pass-through subscribers. That way, we can make sure that we have traces
  * for events which are generated from track positions
  */
trait Tracer extends SubscribingRaceActor with FilteringPublisher {

  val traceCapacity = config.getIntOrElse("trace-capacity", 16)
  val rePublish = config.getBooleanOrElse("re-publish", true)

  val trajectories: MutHashMap[String,TracerEntry] = new MutHashMap

  // override for other storage mechanism
  protected def createTrace: MutTrajectory = new CompressedTrace(traceCapacity)


  def handleTrackMessage: Receive = {
    case BusEvent(_,o: Tracked3dObject,_) =>
      if (pass(o)) {
        updateTrajectories(o)
        if (rePublish) publish(o)
      }

    case BusEvent(_,tm: TrackTerminationMessage,_) =>
      trajectories -= tm.cs
      if (rePublish) publishFiltered(tm)

    case BusEvent(_,msg,_) =>
      if (rePublish) publishFiltered(msg)
  }

  override def handleMessage: Receive = handleTrackMessage


  def updateTrajectories (o: Tracked3dObject): Unit = {
    trajectories.get(o.cs) match {
      case Some(e) =>
        e.obj = o
        e.trajectory.append(o)
      case None =>
        val e = new TracerEntry(o,createTrace)
        e.trajectory.append(o)
        trajectories += o.cs -> e
    }
  }
}
