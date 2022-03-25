/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.common.Nat.N3
import gov.nasa.race.common.{FHTInterpolant, TInterpolant}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.geo.{Euclidean, GeoPosition}
import gov.nasa.race.track.{TrackDropped, TrackPairEvent, TrackTerminationMessage, Tracked3dObject, TrackedObjectExtrapolator}
import gov.nasa.race.trajectory.{TDP3, Trajectory, USTrace}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{Angle, Length, Speed, Time}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.{HashMap => MHashMap, Set => MSet}
import scala.concurrent.duration._



/**
  * actor that detects proximity flights with parallel headings and reports flight combinations which
  * converge exceeding a max heading difference within a given radius, indicating that at least one
  * plane had to roll to an extend that it lost sight of the other
  */
class ParallelApproachAnalyzer (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  //--- settings to detect parallel proximity flights
  val maxParallelDist = config.getLengthOrElse("max-par-dist", Meters(2000))
  val maxParallelAngle = config.getAngleOrElse("max-par-angle", Degrees(10))
  val maxParallelDalt = config.getLengthOrElse("max-par-dalt", Meters(50))

  //--- settings to analyze converging trajectories
  val maxConvergeDist = config.getLengthOrElse("max-conv-dist", Meters(6000))  // about 30 sec flight
  val maxConvergeAngle = config.getAngleOrElse("max-conv-angle", Degrees(30))
  val maxConvergeDuration = config.getFiniteDurationOrElse("max-conv-duration", 120.seconds).toMillis
  val convergeInterval = config.getFiniteDurationOrElse("conv-interval", 1.second).toMillis.toInt

  val eventIdPrefix = config.getStringOrElse("event-id", "angle")
  val eventClassifier = config.getStringOrElse("event-classifier", name)
  var nEvents: Int = 0 // reported events

  class Candidate {
    var track: Tracked3dObject = _
    val estimator = getConfigurableOrElse("estimator")(new TrackedObjectExtrapolator)
    val trajectory = new USTrace(config.getIntOrElse("max-path",50))
    val checked: MSet[String] = MSet.empty

    def update (newTrack: Tracked3dObject): Unit = {
      if (track == null || newTrack.date > track.date) {
        track = newTrack
        estimator.addObservation(newTrack)
        trajectory += newTrack
      }
    }
  }

  val candidates: MHashMap[String,Candidate] = MHashMap.empty

  override def handleMessage = {
    case BusEvent(_,term:TrackTerminationMessage,_) => candidates.remove(term.id)
    case BusEvent(_,track:Tracked3dObject,_) => {
      if (track.isDroppedOrCompleted) {
        candidates.remove(track.id)
      } else {
        updateCandidates(track)
      }
    }
  }

  def updateCandidates(track: Tracked3dObject): Unit = {
    val ownEntry = candidates.getOrElseUpdate(track.id,new Candidate)
    ownEntry.update(track)

    candidates.foreach { e =>
      if (e._1 != track.id) {
        val otherEntry = e._2

        if (!otherEntry.checked.contains(track.id)) {
          val est = otherEntry.estimator
          est.estimateState(track.date.toEpochMillis)
          val d = Euclidean.distance(track.position, est.estimatedPosition)
          if (d < maxParallelDist) {
            if (track.position.altitude - est.altitude < maxParallelDalt) {
              if (absDiff(track.heading, est.heading) < maxParallelAngle) {
                ownEntry.checked += e._1
                otherEntry.checked += track.id
                checkConvergence(ownEntry,otherEntry)
              }
            }
          }
        }
      }
    }
  }

  def checkConvergence (c1: Candidate, c2: Candidate): Unit = {
    val tr1 = new FHTInterpolant[N3,TDP3](c1.trajectory)
    val tr2 = new FHTInterpolant[N3,TDP3](c2.trajectory)

    // c1 was last updated, so we start backwards interpolation from last c2 entry
    val it2 = tr2.reverseTailDurationIterator(maxConvergeDuration, convergeInterval)
    val it1 = tr1.reverseIterator(tr2.tRight, tr2.tRight - maxConvergeDuration, convergeInterval)

    if (it1.hasNext && it2.hasNext){
      var p = it1.next()
      var lastLat1 = p.φ
      var lastLon1 = p.λ

      p = it2.next()
      var lastLat2 = p.φ
      var lastLon2 = p.λ

      var t = 0
      var dist = Length0
      var deltaHdg = Angle0
      val dt = convergeInterval/1000
      var stop = false

      while (!stop && it1.hasNext && it2.hasNext) {
        p = it1.next()
        val lat1 = p.φ
        val lon1 = p.λ
        val alt1 = p.altitude
        val hdg1 = Euclidean.heading(lat1,lon1, lastLat1,lastLon1)


        p = it2.next()
        val lat2 = p.φ
        val lon2 = p.λ
        val alt2 = p.altitude
        val hdg2 = Euclidean.heading(lat2,lon2, lastLat2,lastLon2)


        deltaHdg = Angle.absDiff(hdg1,hdg2)
        dist = Euclidean.distance(lat1,lon1,alt1, lat2,lon2,alt2)

        if (deltaHdg > maxConvergeAngle){ // Bingo - got one
          val date = DateTime.ofEpochMillis(p.millis)
          val pos1 = GeoPosition(lat1,lon1,alt1)
          val spd1 = Euclidean.distance(lat1,lon1,alt1,lastLat1,lastLon1,alt1) / convergeInterval.milliseconds
          val pos2 = GeoPosition(lat2,lon2,alt2)
          val spd2 = Euclidean.distance(lat2,lon2,alt2,lastLat2,lastLon2,alt2) / convergeInterval.milliseconds

          val pos = Euclidean.midPoint(pos1,pos2)

          // TODO should probably include speed and heading for both tracks

          publishEvent(date, pos, deltaHdg, dist, c1, tr1, pos1, hdg1, spd1, c2, tr2, pos2, hdg2, spd2)
          //println(f"@@ $t%4d: ${dist.toMeters}%10.0f, ${hdg1.toDegrees}%3.0f, ${hdg2.toDegrees}%3.0f -> delta= ${deltaHdg.toDegrees}%3.0f")
          info(f"max angle-in exceeded: ${c1.track.cs},${c2.track.cs} at $date: Δhdg = ${deltaHdg.toDegrees}%.0f, dist = ${dist.toMeters}%.0fm")
          stop = true

        } else {
          t += dt
          stop = (dist > maxConvergeDist)
        }

        lastLat1 = lat1
        lastLon1 = lon1

        lastLat2 = lat2
        lastLon2 = lon2
      }
    }
  }

  def reportTrajectory (c: Candidate, tr: TInterpolant[N3,TDP3], dStart: DateTime, dEnd: DateTime, interval: Time, n: Int): Trajectory = {
    val reportTrajectory = c.trajectory.emptyMutable(n)
    reportTrajectory ++= tr.iterator(dStart.toEpochMillis, dEnd.toEpochMillis, interval.toMillis)
    reportTrajectory
  }

  def publishEvent(date: DateTime, pos: GeoPosition, deltaHdg: Angle, dist: Length,
                   c1: Candidate, tr1: TInterpolant[N3,TDP3], pos1: GeoPosition, hdg1: Angle, spd1: Speed,
                   c2: Candidate, tr2: TInterpolant[N3,TDP3], pos2: GeoPosition, hdg2: Angle, spd2: Speed): Unit = {

    val dur = Time.min( Seconds(60), c1.trajectory.getDuration, c2.trajectory.getDuration)

    val dEnd = c2.trajectory.getLastDate
    val dStart = dEnd - dur
    val interval = Seconds(1)
    val n = (dur / interval).toInt + 1

    val t1 = reportTrajectory(c1, tr1, dStart, dEnd, interval, n)
    val t2 = reportTrajectory(c2, tr2, dStart, dEnd, interval, n)

    nEvents += 1
    val ev = TrackPairEvent(
      s"$eventIdPrefix-$nEvents",
      date, pos,
      "angle-in",
      f"${deltaHdg.toDegrees}%3.0f° at ${dist.toMeters}%5.0fm",
      eventClassifier,
      c1.track, pos1, hdg1, spd1, t1,
      c2.track, pos2, hdg2, spd2, t2,
    )

    publish(ev)
  }
}
