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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.{BufferRecord, RecordWriter}
import gov.nasa.race.config._
import gov.nasa.race.core.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, SubscribingRaceActor}
import gov.nasa.race.track._

import scala.concurrent.duration._

/**
  * a generic actor that periodically creates BufferRecord snapshots
  */
class TrackRecordStoreActor (val config: Config) extends SubscribingRaceActor with ContinuousTimeRaceActor with PeriodicRaceActor {

  override def defaultTickInterval = 30.seconds

  val writer: RecordWriter[BufferRecord] = createWriter

  protected def createWriter = getConfigurableOrElse[RecordWriter[BufferRecord]]("writer") {
    new TrackRecordWriter(NoConfig)
  }

  override def onRaceTick(): Unit = writer.store

  override def handleMessage: Receive = {
    case BusEvent(_,track: Tracked3dObject,_) =>
      if (track.isDroppedOrCompleted) writer.remove(track.id,track.date) else writer.update(track.id,track.date,track)

    case BusEvent(_,m: TrackTerminationMessage,_) =>
      writer.remove(m.id,m.date)
  }
}
