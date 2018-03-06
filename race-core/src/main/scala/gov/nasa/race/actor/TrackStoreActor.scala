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

import java.io.RandomAccessFile
import java.nio.channels.FileChannel

import com.typesafe.config.Config
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, SubscribingRaceActor}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.track.TrackedObject

import scala.collection.mutable.{HashMap => MHashMap}

/**
  * actor that periodically stores sets of tracks as arrays of binary records in a memory mapped file.
  *
  * Note that the array is dense, i.e. we move records upon completion/drop of a track (only the last
  * record is moved, and new ones are added at the end)
  */
class TrackStoreActor (val config: Config) extends SubscribingRaceActor with ContinuousTimeRaceActor with PeriodicRaceActor {

  val maxTracks = config.getIntOrElse("max-tracks",5000)
  val pathName = config.getString("pathname")

  val indexMap = MHashMap.empty[String,Int]
  val length = maxTracks * 44 + 8 + 4 // FIXME - this should be computed by a configurable BufferRecord
  val bbuf = new RandomAccessFile("howtodoinjava.dat", "rw").getChannel().map(FileChannel.MapMode.READ_WRITE, 0, length)

  override def handleMessage = {
    case BusEvent(_,track:TrackedObject,_) =>
  }

}
