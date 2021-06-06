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

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.geo.Euclidean
import gov.nasa.race.track.{ProximityEvent, TrackPairEvent}

/**
  * Tracer actor that generates TrajectoryPairEvents (with trajectories) from ProximityEvents it receives from
  * downstream actors
  */
class TrackPairEventActor (val config: Config) extends Tracer {

  val eventPrefix = config.getStringOrElse("event-prefix", "TPE")
  val eventClassifier = config.getStringOrElse("event-classifier", name)

  var nEvents = 0

  val writeEventsTo = config.getString("write-events-to")

  def handleProximity: Receive = {
    case BusEvent(_,prox: ProximityEvent,_) => createPairEvent(prox).foreach( publish(writeEventsTo,_))
  }

  override def handleMessage: Receive = handleProximity orElse handleTrackMessage

  def createPairEvent(prox: ProximityEvent): Option[TrackPairEvent] = {
    if (prox.isNew || prox.isCollision) {
      for ( e1 <- trajectories.get(prox.ref.id); e2 <- trajectories.get(prox.track.cs) ) {
        val o1 = e1.obj
        val o2 = e2.obj

        nEvents += 1
        val tpe = new TrackPairEvent(s"$eventPrefix-$nEvents",
          prox.date,
          Euclidean.midPoint(prox.ref.position, prox.track.position),
          eventType(prox),
          eventDetails(prox),
          eventClassifier,
          o1, prox.ref.position, o1.heading, o1.speed, e1.trajectory.snapshot,
          o2, prox.track.position, prox.track.heading, prox.track.speed, e2.trajectory.snapshot
        )

        return Some(tpe)
      }
    }
    None // not a new proximity event
  }

  def eventType (prox: ProximityEvent): String = prox.eventType

  def eventDetails (prox: ProximityEvent): String = {
    s"${prox.distance.toMeters.toInt}m"
  }
}
