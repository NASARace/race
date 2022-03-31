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
import gov.nasa.race._
import gov.nasa.race.common.Nat.N3
import gov.nasa.race.common.{LinTInterpolant, TInterpolant}
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{RaceContext, SubscribingRaceActor}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.{Euclidean, GeoPosition}
import gov.nasa.race.track.{TrackListMessage, TrackPairEvent, TrackTerminationMessage, Tracked3dObject, TrackedObject, TrackedObjects}
import gov.nasa.race.trajectory._
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.{HashMap => MutHashMap}
import scala.concurrent.duration._


/**
  * actor that stores trajectories for tracks received from different channels in order to compute
  * positional discrepancies between those channels.
  * We add tracks as soon as the first message passes the (area) filter, we analyze each
  * diffTrajectory once it is closed and we have a closed refTrajectory
  *
  * Note this actor needs to subscribe to exactly two channels
  *
  * note also the filter(s) are applied to incoming track messages
  */
class TrackDiffActor (val config: Config) extends SubscribingRaceActor with FilteringPublisher {

  class TrackDiffEntry (val id: String, val trajectories: Array[MutTrajectory]) {
    var isClosed: Array[Boolean] = new Array(trajectories.size)
    val objs: Array[TrackedObject] = new Array(trajectories.size)

    // set once the reference trajectory is complete
    var refTrajectory: Option[Trajectory] = None
    var refInterpolant: Option[TInterpolant[N3,TDP3]] = None

    def setClosed(i: Int): Unit = isClosed(i) = true
    def allClosed: Boolean = !isClosed.exists(_ == false)

    def setRefTrajectory: Unit = {
      val tr = trajectories(0).snapshot
      refTrajectory = Some(tr)
      refInterpolant = Some(new LinTInterpolant[N3,TDP3](tr))
    }

    def foreachDiffTrajectory(f: (Int,Trajectory)=>Unit): Unit = {
      var i = 1
      while (i < trajectories.length) {
        f(i, trajectories(i))
        i += 1
      }
    }

    def copyTrajectory(i: Int, other: TrackDiffEntry): Unit = {
      trajectories(i) = other.trajectories(i)
      isClosed(i) = other.isClosed(i)
      if (i == 0 && other.refTrajectory.isDefined) {
        refTrajectory = other.refTrajectory
        refInterpolant = other.refInterpolant
      }
    }
  }

  val eventClassifier = config.getStringOrElse("event-classifier", name)
  val tracks = new MutHashMap[String,TrackDiffEntry]
  val posFilter = getConfigurableOrElse[Filter[GeoPosition]]("pos-filter")(new PassAllFilter[GeoPosition])

  val checkClose = config.getBooleanOrElse("close-check", false)

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(raceContext,actorConf) && (readFrom.length > 1)
  }

  protected def createTrajectories: Array[MutTrajectory] = {
    readFrom.toArray.map( _ => new MutCompressedTrajectory(32))
  }

  def handleTrackMessage: Receive = {
    case BusEvent(chan,track:TrackedObject, _) => updateTracks(chan,track)
    case BusEvent(chan,tracks:TrackedObjects[_], _) => tracks.foreach( updateTracks(chan,_))
    case BusEvent(chan,tm: TrackTerminationMessage,_) => closeTrack(chan,tm.cs)
  }

  override def handleMessage: Receive = handleTrackMessage

  @inline def channelIndex(chan: String): Int = readFrom.indexOf(chan)

  protected def updateTracks(chan: String, o: TrackedObject): Unit = {
    if (pass(o)) { // if we have a filter, only keep trajectories of tracks that pass
      val channelIdx = channelIndex(chan)
      val e = tracks.getOrElseUpdate(o.cs, new TrackDiffEntry(o.cs, createTrajectories))

      if (o.isChangedCS) {
        // we can't just rename the old entry since other channels might already have
        // reported tracks for the new CS
        for ( oldCS <- o.getOldCS; oldEntry <- tracks.get(oldCS)) {
          e.copyTrajectory(channelIdx, oldEntry)
        }
      }

      e.trajectories(channelIdx).appendIfNew(o)

      e.objs(channelIdx) = o
      if (o.isDroppedOrCompleted) closeTrack(chan,o.cs)

    } else { // check if this was a recorded track leaving the area of interest
      closeTrack(chan,o.cs)
    }
  }

  protected def closeTrack(chan: String, id: String): Unit = closeTrack(channelIndex(chan), id)

  protected def closeTrack (chanIdx: Int, id: String): Unit = {
    ifSome(tracks.get(id)) { e=>
      if (!e.isClosed(chanIdx)) {
        e.setClosed(chanIdx)
        if (chanIdx == 0 && e.trajectories(chanIdx).size > 1) { // reference trajectory is done
          e.setRefTrajectory
          e.foreachDiffTrajectory { (idx,tr) =>
            if (e.isClosed(idx)) analyzeTrajectoryPair(e,idx)
            else scheduleCloseCheck(e,idx)
          }
        } else {
          analyzeTrajectoryPair(e,chanIdx)
        }
        if (e.allClosed) { // avoid this track gets re-opened after exiting into capture zone
          scheduleOnce(30.seconds){
            tracks -= id
          }
        }
      }
    }
  }

  protected def analyzeTrajectoryPair(e: TrackDiffEntry, chanIdx: Int): Unit = {
    val diffTraj = e.trajectories(chanIdx)
    for (refTraj <- e.refTrajectory; refInter <- e.refInterpolant) {
      //refTraj.dump
      //diffTraj.dump
      //println(diffTraj.arithmeticMidDataPoint)

      val date: DateTime = refTraj.getLastDate
      val td = TrajectoryDiff.calculate(
        readFrom(0), refTraj,
        readFrom(chanIdx), diffTraj,
        (t) => refInter,
        posFilter.pass,
        Euclidean.distance2D,
        Euclidean.heading)

      ifSome(td) { report(e.id, date, chanIdx, e, _) }
    }
  }

  protected def report (id: String, date: DateTime, chanIdx: Int, e: TrackDiffEntry, td: TrajectoryDiff): Unit = {
    val pRef = td.maxDistanceRefPos
    val pDiff = td.maxDistanceDiffPos
    val pEvent = Euclidean.midPoint(pRef,pDiff)
    val tEvent = td.maxDistanceTime

    val vRef = td.refTrajectory.computeVelocity2D(tEvent, Euclidean.distance2D, Euclidean.heading)
    val vDiff = td.diffTrajectory.computeVelocity2D(tEvent, Euclidean.distance2D, Euclidean.heading)

    val event = TrackPairEvent(
      id,
      tEvent, pEvent,
      "MaxDeviation",
      s"max track deviation ${readFrom(0)} : ${readFrom(chanIdx)}",
      eventClassifier,
      e.objs(0), pRef, vRef.heading, vRef.speed, td.refTrajectory,
      e.objs(chanIdx), pDiff, vDiff.heading, vDiff.speed, td.diffTrajectory,
      Some(td)
    )

    publish(event)

    // println(s"@@ trackdiff $id on ${readFrom(chanIdx)} : ${td.distance2DStats.numberOfSamples}")
    // println(f"  dist  mean=${td.distance2DStats.mean.showRounded}, min=${td.distance2DStats.min.showRounded}, max=${td.distance2DStats.max.showRounded} σ²=${td.distance2DStats.variance}%.4f " )
    // println(f"  angle mean=${td.angleDiffStats.mean.showRounded}, min=${td.angleDiffStats.min.showRounded}, max=${td.angleDiffStats.max.showRounded} σ²=${td.angleDiffStats.variance}%.4f ")
  }

  protected def scheduleCloseCheck (e: TrackDiffEntry, chanIdx: Int): Unit = {
    if (checkClose){
      val avgUpdate = e.trajectories(chanIdx).getAverageUpdateDuration
      if (avgUpdate.isDefined) {
        val lastUpdate = e.trajectories(chanIdx).getLastDate
        scheduleOnce((avgUpdate * 1.5 * timeScale).toFiniteDuration) {
          closeCheck(e, chanIdx, lastUpdate)
        }
      }
    }
  }

  protected def closeCheck (e: TrackDiffEntry, chanIdx: Int, lastUpdate: DateTime): Unit = {
    if (!e.isClosed(chanIdx)){
      if (lastUpdate == e.trajectories(chanIdx).getLastDate){ // no updates
        e.setClosed(chanIdx)
        analyzeTrajectoryPair(e, chanIdx)
      } else {
        scheduleCloseCheck(e, chanIdx)
      }
    }
  }
}
