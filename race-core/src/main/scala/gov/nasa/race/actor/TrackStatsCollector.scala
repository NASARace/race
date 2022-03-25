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
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.common.{PrintStats, Stats}
import gov.nasa.race.core.{BusEvent, RaceTick}
import gov.nasa.race.track.{TrackListMessage, TrackTerminationMessage, Tracked3dObject}
import gov.nasa.race.uom.DateTime

import scala.concurrent.duration._
import scala.collection.mutable.{HashMap => MutHashMap}

/**
  * Stats object for all track updates we receive. Since those refer to different
  * tracks we don't extend/use TSEntryData, which would only make sense for single objects.
  */
class TrackStats (val topic: String, val source: String) extends PrintStats {
  var takeMillis: Long = 0
  var elapsedMillis: Long = 0

  var nLive: Int = 0
  var maxLive: Int = 0
  var nCompleted: Int = 0
  var nNew: Int = 0
  var nUpdates: Int = 0

  var nOutOfOrder: Int = 0
  var nAmbiguous: Int = 0
  var nDuplicates: Int = 0
  var nBlackout: Int = 0
  var nDropped: Int = 0

  override def printWith(pw: PrintWriter): Unit = {
    val rate = (nUpdates * 1000.0) / elapsedMillis
    pw.println("                          tracks              changes                      anomalies")
    pw.println(" updates     rate       live  maxLive        new     term      drop   black   order     dup   ambig")
    pw.println("--------  -------    -------  -------    -------  -------    ------  ------  ------  ------  ------")
    pw.println(f"$nUpdates%8d  $rate%7.1f    $nLive%7d  $maxLive%7d    $nNew%7d  $nCompleted%7d    $nDropped%6d  $nBlackout%6d  $nOutOfOrder%6d  $nDuplicates%6d  $nAmbiguous%6d")
  }

  def snapshot (simTime: Long, elapsedSimTime: Long): Stats = {
    yieldInitialized(clone.asInstanceOf[TrackStats]) {snap =>
      snap.takeMillis = simTime
      snap.elapsedMillis = elapsedSimTime
    }
  }
}

class TrackStatsSourceEntry(val source: Option[String]) {
  val liveTracks = new MutHashMap[String, Tracked3dObject]
  val completions = new MutHashMap[String, DateTime]

  def checkExpirations (ts: TrackStats, simMillis: Long, dropAfterMillis: Long): Unit = {
    completions.foreach { e =>
      if (simMillis - e._2.toEpochMillis > dropAfterMillis){
        completions -= e._1
      }
    }

    liveTracks.foreach { e =>
      val cs = e._1
      val track = e._2

      if ((simMillis - track.date.toEpochMillis) >= dropAfterMillis) {
        if (!completions.contains(cs)) {
          ts.nDropped += 1
        }
      }
    }
  }

  def update (ts: TrackStats, track: Tracked3dObject, dropAfterMillis: Long): Unit = {
    val cs = track.cs

    ts.nUpdates += 1

    liveTracks.get(cs) match {
      case Some(t) =>
        if (t.date > track.date) { // out of order
          ts.nOutOfOrder += 1

        } else if (t.date == track.date){ // duplicate or ambiguous
          if (t.position == track.position && t.speed == track.speed && t.heading == track.heading) {
            ts.nDuplicates += 1
          } else {
            ts.nAmbiguous += 1
          }
        }

        if (track.isDroppedOrCompleted){
          completions += (cs -> track.date)
          ts.nCompleted += 1

          liveTracks -= cs
          ts.nLive -= 1

        } else {
          // check blackout violation
          ifSome(completions.get(cs)) { completionDate =>
            if (track.date.toEpochMillis - completionDate.toEpochMillis <= dropAfterMillis) {
              ts.nBlackout += 1
            }
          }

          liveTracks += cs -> track // just an update, size hasn't changed
        }

      case None => // new track
        ts.nNew += 1
        liveTracks += cs -> track
        ts.nLive += 1
        if (ts.nLive > ts.maxLive) ts.maxLive = ts.nLive
    }
  }

  def terminate (ts: TrackStats, tm: TrackTerminationMessage): Unit = {
    val cs = tm.cs
    ifSome(liveTracks.get(cs)) { t =>
      completions += (cs -> tm.date)
      ts.nCompleted += 1

      liveTracks -= cs
      ts.nLive -= 1
    }
  }
}

/**
  * actor to collect basic track stats
  *
  * note this is not a TSStatsCollector since we don't keep per-track statistics. This causes
  * some redundancy but is more efficient for a large number of tracks
  */
class TrackStatsCollector (val config: Config) extends StatsCollectorActor {

  // note this is sim time and only gets checked before we publish
  // note also we use this for both drop and blackout checks
  val dropAfterMillis = config.getFiniteDurationOrElse("drop-after", 60.seconds).toMillis

  val stats = new TrackStats(title,channels)

  val sources = new MutHashMap[Option[String],TrackStatsSourceEntry]

  def getSource (id: Option[String]): TrackStatsSourceEntry = sources.getOrElseUpdate(id, new TrackStatsSourceEntry(id))

  def checkExpirations: Unit = {
    val curSimMillis = updatedSimTimeMillis
    sources.foreach( e=> e._2.checkExpirations(stats,curSimMillis,dropAfterMillis))
  }

  override def onRaceTick(): Unit = {
    checkExpirations
    publishSnapshot
  }

  override def handleMessage = {
    case BusEvent(_, track: Tracked3dObject, _) => update(track)
    case BusEvent(_, tlm: TrackListMessage, _) => tlm.tracks.foreach(update)
    case BusEvent(_, term: TrackTerminationMessage, _) => terminate(term)
  }

  def update (t: Tracked3dObject): Unit = getSource(t.source).update(stats,t, dropAfterMillis)

  def terminate (tm: TrackTerminationMessage): Unit = getSource(tm.source).terminate(stats,tm)

  def publishSnapshot: Unit = {
    val takeMillis = updatedSimTimeMillis
    val elapsedMillis = elapsedSimTimeMillisSinceStart
    publish(stats.snapshot(takeMillis,elapsedMillis))
  }
}
