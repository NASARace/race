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
import gov.nasa.race.core.{BusEvent, RaceTick}
import gov.nasa.race.track.TrackPairEvent
import gov.nasa.race.trajectory.TrajectoryDiff
import gov.nasa.race.uom.{Angle, Length}

import scala.collection.mutable.{HashMap => MutHashMap}

class TrackDiffStatsCollector (val config: Config) extends StatsCollectorActor {

  /**
    * the collector and stats rolled in one. We cut some corners here since the
    * snapshot objects are not immutable so make sure snapshot objects are not modified
    */
  class TrackDiffStats (val area: String, val source: String) extends PrintStats {
    var takeMillis: Long = 0
    var elapsedMillis: Long = 0

    var trackDiffs: Seq[TrackPairEvent] = Seq.empty

    var maxDistance: Length = Length.UndefinedLength
    var minDistance: Length = Length.UndefinedLength
    var avgDistance: Length = Length.UndefinedLength
    var avgAngle: Angle = Angle.UndefinedAngle

    def topic: String = area

    def update (e: TrackPairEvent): Unit = {
      trackDiffs = trackDiffs :+ e

      e.withExtra { td: TrajectoryDiff =>
        if (maxDistance.isUndefined){
          maxDistance = td.distance2DStats.max
          minDistance = td.distance2DStats.min
          avgDistance = td.distance2DStats.mean
          avgAngle = td.angleDiffStats.mean
        } else {
          if (maxDistance < td.distance2DStats.max) maxDistance = td.distance2DStats.max
          if (minDistance < td.distance2DStats.min) minDistance = td.distance2DStats.min
          avgDistance = avgDistance + (td.distance2DStats.mean - avgDistance)/trackDiffs.size
          avgAngle = avgAngle + (td.angleDiffStats.mean - avgAngle)/trackDiffs.size
        }
      }
    }

    def snapshot (simTime: Long, elapsedSimTime: Long): Stats = {
      yieldInitialized(clone.asInstanceOf[TrackDiffStats]){ snap =>
        snap.takeMillis = simTime
        snap.elapsedMillis = elapsedSimTime
      }
    }

    override def printWith(pw: PrintWriter): Unit = {
      pw.println("                               distance [m]                 angle [deg]")
      pw.println("count  (track)        max     min    mean  (variance)     avg  (variance)")
      pw.println("----- --------    ------- ------- -------  ----------    ----  ----------")
      pw.print(f"${trackDiffs.size}%5d             ")
      pw.print(f"${maxDistance.toRoundedMeters}%7d ")
      pw.print(f"${minDistance.toRoundedMeters}%7d ")
      pw.print(f"${avgDistance.toRoundedMeters}%7d                ")
      pw.println(f"${avgAngle.toRoundedDegrees}%4d")
      pw.println
      trackDiffs.foreach { e =>
        e.withExtra{ td: TrajectoryDiff =>
          pw.print(f"      ${e.id}%8s    ")
          pw.print(f"${td.distance2DStats.max.toRoundedMeters}%7d ")
          pw.print(f"${td.distance2DStats.min.toRoundedMeters}%7d ")
          pw.print(f"${td.distance2DStats.mean.toRoundedMeters}%7d  ")
          pw.print(f"${td.distance2DStats.sampleVariance}%10.2f    ")
          pw.print(f"${td.angleDiffStats.mean.toRoundedDegrees}%4d  ")
          pw.print(f"${td.angleDiffStats.sampleVariance}%10.2f")
          pw.println
        }
      }
    }

    override def toXML: String = {
      s"""      <trackDiff area="$topic" tracks="$trackDiffs.size">
        <maxDistance uom="meters">${maxDistance.toRoundedMeters}</maxDistance>
        <minDistance uom="meters">${minDistance.toRoundedMeters}</minDistance>
        <avgDistance uom="meters">${avgDistance.toRoundedMeters}</avgDistance>
        <avgAngle uom="degrees">${avgAngle.toRoundedDegrees}</avgAngle>
        <tracks>
           ${trackDiffs.map(tpeToXML).mkString("\n        ")}
        </tracks>
      </trackDiff>"""
    }

    def tpeToXML (e: TrackPairEvent): String = {
      val td = e.extraData.asInstanceOf[TrajectoryDiff]
      s"""      <track id="${e.id}" time="${e.date}">
        <maxDistance uom="meters">${td.distance2DStats.max.toRoundedMeters}</maxDistance>
        <minDistance uom="meters">${td.distance2DStats.min.toRoundedMeters}</minDistance>
        <avgDistance uom="meters">${td.distance2DStats.mean.toRoundedMeters}</avgDistance>
        <varDistance>${td.distance2DStats.sampleVariance}</varDistance>
        <avgAngle uom="degrees">${td.angleDiffStats.mean.toRoundedDegrees}</avgAngle>
        <varAngle>${td.angleDiffStats.sampleVariance}</varAngle>
      </track>"""
    }
  }

  val areas: MutHashMap[String,TrackDiffStats] = MutHashMap.empty

  override def onRaceTick(): Unit = publishSnapshots

  override def handleMessage = {
    case BusEvent(chan, e:TrackPairEvent, _) => update(chan,e)
  }

  def update (chan: String, e: TrackPairEvent): Unit = {
    e.withExtra[TrajectoryDiff] { td=>
      val collector = areas.getOrElseUpdate(e.classifier, new TrackDiffStats(e.classifier, chan))
      collector.update(e)
    }
    // ignore others
  }

  def publishSnapshots: Unit = {
    val takeMillis = updatedSimTimeMillis
    val elapsedMillis = elapsedSimTimeMillisSinceStart
    val snaps = areas.toSeq.sortBy(_._1).map(_._2.snapshot(takeMillis,elapsedMillis))
    snaps.foreach(publish)
  }
}
