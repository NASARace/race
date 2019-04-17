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
import gov.nasa.race.common.{LinTInterpolant, TInterpolant}
import gov.nasa.race.common.Nat.N3
import gov.nasa.race.core.{RaceContext, RaceInitializeException, SubscribingRaceActor}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.{Euclidean, GeoPosition}
import gov.nasa.race.uom.Time._
import gov.nasa.race.track.{TrackListMessage, TrackTerminationMessage, TrackedObject}
import gov.nasa.race.trajectory.{MutCompressedTrajectory, MutTrajectory, TDP3, Trajectory, TrajectoryDiff}
import gov.nasa.race.uom.{Angle, Length}

import scala.collection.mutable.{HashMap => MutHashMap}
import scala.concurrent.ExecutionContext.Implicits.global

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

  // used to check if track entry for a given input channel should be closed
  case class CheckClosed (chanIdx: Int, id: String)

  class TrackDiffEntry (val id: String, val trajectories: Array[MutTrajectory]) {
    var isClosed: Array[Boolean] = new Array(trajectories.size)

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

    def foreachClosedDiffTrajectory(f: (Trajectory)=>Unit): Unit = {
      var i = 1
      while (i < trajectories.length) {
        if (isClosed(i)) f(trajectories(i))
        i += 1
      }
    }
  }

  val tracks = new MutHashMap[String,TrackDiffEntry]
  val posFilter: Filter[GeoPosition]= getConfigurableOrElse("pos-filter")(new PassAllFilter[GeoPosition])

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(raceContext,actorConf) && (readFrom.length > 1)
  }

  protected def createTrajectories: Array[MutTrajectory] = {
    Array[MutTrajectory](new MutCompressedTrajectory(32),new MutCompressedTrajectory(32))
  }

  def handleTrackMessage: Receive = {
    case BusEvent(chan,o: TrackedObject, _) => updateTracks(chan,o)
    case BusEvent(chan,tm: TrackListMessage, _) => tm.tracks.foreach( updateTracks(chan,_))
    case BusEvent(chan,tm: TrackTerminationMessage,_) => closeTrack(chan,tm.cs)
    case CheckClosed(chanIdx,id) => closeTrack(chanIdx,id,false)
  }

  override def handleMessage: Receive = handleTrackMessage

  @inline def channelIndex(chan: String): Int = readFrom.indexOf(chan)

  protected def updateTracks(chan: String, o: TrackedObject): Unit = {
    if (pass(o)) { // if we have a filter, only keep trajectories of tracks that pass
      val e = tracks.getOrElseUpdate(o.cs, new TrackDiffEntry(o.cs, createTrajectories))
      e.trajectories(channelIndex(chan)) += o
      if (o.isDroppedOrCompleted) closeTrack(chan,o.cs)

    } else { // check if this was a recorded track leaving the area of interest
      closeTrack(chan,o.cs)
    }
  }

  protected def closeTrack(chan: String, id: String): Unit = closeTrack(channelIndex(chan), id, true)

  protected def closeTrack (chanIdx: Int, id: String, recheck: Boolean): Unit = {
    ifSome(tracks.get(id)) { e=>
      if (!e.isClosed(chanIdx)) {
        e.setClosed(chanIdx)
        if (chanIdx == 0) { // reference trajectory is done
          e.setRefTrajectory
          e.foreachClosedDiffTrajectory { tr =>
            report(diff(e.refTrajectory.get, e.refInterpolant.get, tr))
          }
        } else {
          ifSome(e.refTrajectory){ refTr =>
            report(diff(refTr, e.refInterpolant.get, e.trajectories(chanIdx)))
          }
        }
        if (e.allClosed) tracks -= id
      }
    }
  }

  protected def diff (refTraj: Trajectory, refIntr: TInterpolant[N3,TDP3], diffTraj: Trajectory): Option[TrajectoryDiff] = {
    TrajectoryDiff.calculate(refTraj, diffTraj,
                             (t) => refIntr, posFilter.pass, Euclidean.distance2D, Euclidean.heading)
  }

  protected def report (trDiff: Option[TrajectoryDiff]): Unit = {
    ifSome(trDiff) { td=>
      println(s"@@@ diff ")
    }
  }
}
