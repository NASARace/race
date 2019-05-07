package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.common.Stats
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.track.TrackPairEvent
import gov.nasa.race.trajectory.TrajectoryDiff
import gov.nasa.race.uom.{Angle, Length}

import scala.collection.mutable.{HashMap => MutHashMap}

class TrackDiffStatsCollector (val config: Config) extends StatsCollectorActor {

  class TrackDiffCollector (val area: String) {
    var trackDiffs: Seq[TrackPairEvent] = Seq.empty

    var maxDistance: Length = Length.UndefinedLength
    var minDistance: Length = Length.UndefinedLength
    var avgDistance: Length = Length.UndefinedLength
    var avgAngle: Angle = Angle.UndefinedAngle

    def += (e: TrackPairEvent): Unit = {
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
  }

  val areas: MutHashMap[String,TrackDiffCollector] = MutHashMap.empty

  override def handleMessage = {
    case BusEvent(_, e:TrackPairEvent, _) => update(e)

    case RaceTick => publish(snapshot)
  }

  def update (e: TrackPairEvent): Unit = {
    e.withExtra[TrajectoryDiff] { td=> areas.getOrElseUpdate(e.classifier, new TrackDiffCollector(e.classifier)) += e }
    // ignore others
  }

  def snapshot: Stats = null
}
