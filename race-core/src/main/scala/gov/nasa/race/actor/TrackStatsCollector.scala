/*
 * Copyright (c) 2019, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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

import java.io.PrintWriter

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{PrintStats, Stats}
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.track.{TrackListMessage, TrackTerminationMessage, TrackedObject}

import scala.collection.mutable.{HashMap => MutHashMap}

class TrackStats (val topic: String, val source: String) extends PrintStats {
  var takeMillis: Long = 0
  var elapsedMillis: Long = 0

  var nLive: Int = 0
  var maxLive: Int = 0
  var nTerminated: Int = 0
  var nUpdates: Int = 0

  var nOutOfOrder: Int = 0
  var nAmbiguous: Int = 0
  var nDuplicates: Int = 0

  //... and more to follow

  override def printWith(pw: PrintWriter): Unit = {
    pw.println(" tracks  maxLive     term  updates     order     dup   ambig")
    pw.println("-------  -------  -------  -------    ------  ------  ------")
    pw.println(f"$nLive%7d  $maxLive%7d  $nTerminated%7d  $nUpdates%7d    $nOutOfOrder%6d  $nDuplicates%6d  $nAmbiguous%6d")
  }

  def snapshot (simTime: Long, elapsedSimTime: Long): Stats = {
    yieldInitialized(clone.asInstanceOf[TrackStats]) {snap =>
      snap.takeMillis = simTime
      snap.elapsedMillis = elapsedSimTime
    }
  }
}

/**
  * actor to collect basic track stats
  */
class TrackStatsCollector (val config: Config) extends StatsCollectorActor {

  val tracks = new MutHashMap[String,TrackedObject]
  val stats = new TrackStats(title,channels)

  override def handleMessage = {
    case BusEvent(_, track: TrackedObject, _) => update(track)
    case BusEvent(_, tlm: TrackListMessage, _) => tlm.tracks.foreach(update)
    case BusEvent(_, term: TrackTerminationMessage, _) =>

    case RaceTick => publishSnapshot
  }

  def update (track: TrackedObject): Unit = {
    val cs = track.cs

    tracks.get(cs) match {
      case Some(t) =>
        stats.nUpdates += 1
        if (t.date.isAfter(track.date)) {
          stats.nOutOfOrder += 1
        } else if (t.date.equals(track.date)){ // duplicate or ambiguous
          if (t.position == track.position && t.speed == track.speed && t.heading == track.heading) {
            stats.nDuplicates += 1
          } else {
            stats.nAmbiguous += 1
          }
        }

      case None =>
        stats.nLive += 1
        if (stats.nLive > stats.maxLive) stats.maxLive = stats.nLive
    }
    tracks += cs -> track
  }

  def terminate (term: TrackTerminationMessage): Unit = {
    tracks -= term.cs
    stats.nTerminated += 1
    stats.nLive -= 1
  }

  def publishSnapshot: Unit = {
    val takeMillis = updatedSimTimeMillis
    val elapsedMillis = elapsedSimTimeMillisSinceStart
    publish(stats.snapshot(takeMillis,elapsedMillis))
  }
}
