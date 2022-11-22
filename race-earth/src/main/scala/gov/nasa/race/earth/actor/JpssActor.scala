/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.earth.actor

import gov.nasa.race.common.Utf8CsvPullParser
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.{Failure, SuccessValue, ifSome}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PublishingRaceActor}
import gov.nasa.race.earth.{ViirsHotspot, ViirsHotspotParser}
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
import gov.nasa.race.space.{OreKit, OverpassSeq, TLE}
import gov.nasa.race.trajectory.Trajectory
import gov.nasa.race.uom.Angle.{Degrees, HalfPi, Pi}
import gov.nasa.race.uom.DateTime.UndefinedDateTime
import gov.nasa.race.uom.Time.{Days, Minutes, Seconds, UndefinedTime}
import gov.nasa.race.uom.{Angle, DateTime, Length, Time}

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.{Queue => MutQueue}
import scala.concurrent.duration.DurationInt

/**
 * common root for JpssImportActor and JpssReplayActor
 */
trait JpssActor extends PublishingRaceActor with ContinuousTimeRaceActor {

  class HotspotParser extends Utf8CsvPullParser with ViirsHotspotParser {

    def parse (data: Array[Byte]): Seq[ViirsHotspot] = {
      val hotspots = ArrayBuffer.empty[ViirsHotspot]

      if (initialize(data)) {
        skipToNextRecord() // first line is header
        while (hasMoreData) {
          parseHotspot(satId) match {
            case SuccessValue(hs) => hotspots += hs
            case Failure(err) => warning(s"error parsing $source: $err")
          }
        }
      }

      hotspots.toSeq
    }
  }

  //--- configuration data
  val satId = config.getInt("satellite") // NORAD cat ID
  val source = config.getString("source") // e.g. VIIRS_NOAA20_NRT (instrument+satellite+dataset)
  val overpassBounds = config.getGeoPositionArray("region") // polygon of GeoPositions
  val history = config.getFiniteDurationOrElse("history", 1.day) // for initial request
  val maxScanAngle = Degrees( config.getDoubleOrElse("max-scan", 56.2)) // max VIIRS scan angle
  val overpassMargin = config.getDurationTimeOrElse("overpass-margin", Minutes(5))
  val trajectoryTimeStep = config.getDurationTimeOrElse("trajectory-timestep", Seconds(30))

  //--- the operational data
  var tle: Option[TLE] = None // the current TLE
  var overpasses: MutQueue[OverpassSeq] = MutQueue.empty  // computed when we get a new TLE
  var maxOverpassDuration: Time = UndefinedTime           // computed from TLE

  def satName = tle.flatMap(_.name)

  def getOverpasses(backDays: Long): Array[OverpassSeq] = {
    tle match {
      case Some(tle) =>
        val startDate = currentSimTime - Days(backDays)
        val dur = Days(backDays + 1) // compute overpasses from history start to next 24h
        val opss = OreKit.computeOverpassPeriods( tle, startDate, dur, overpassBounds, maxScanAngle)
        opss.foreach( ops=> ops.trajectory = Some(getOverpassSeqTrajectory(tle,ops)))
        opss

      case None =>
        warning(s"computing overpasses for sat $satId failed (no TLE)")
        Array.empty
    }
  }

  def getOverpassSeqTrajectory (tle: TLE, ops: OverpassSeq): Trajectory = {
    val start = ops.firstDate.toPrecedingMinute - overpassMargin
    val end = ops.lastDate.toFollowingMinute + overpassMargin
    OreKit.computeOrbit( tle, start, trajectoryTimeStep, end)
  }

  // TODO - we should check TLE epoch here (history might be too long for current)
  def getOverpassTrajectories: Array[Trajectory] = {
    val trjs = new ArrayBuffer[Trajectory]( overpasses.size)
    overpasses.foreach { os=>
      // give it some leeway as archived hotspots sometime are timed slightly outside overpass
      val start = os.firstDate.toPrecedingMinute - overpassMargin
      val end = os.lastDate.toFollowingMinute + overpassMargin
      trjs += OreKit.computeOrbit( tle.get, start, trajectoryTimeStep, end)

    }
    trjs.toArray
  }

  def nextOverpassDate: Option[DateTime] = {
    val now = currentSimTime
    overpasses.find( os=> os.lastDate > now).map( _.lastDate)
  }

  // get first overpass end date that is more recent than the latest pixel date in this data set
  // note this just rolls up older history data into our first computed overpass time
  def getOverpassDateForDataSet(hotspots: Seq[ViirsHotspot]): DateTime = {
    val lastPixDate = hotspots.foldLeft(UndefinedDateTime)( (d,h)=> if (h.date > d) h.date else d)
    overpasses.foreach { ops=>
      if (ops.lastDate > lastPixDate) return ops.lastDate
    }
    UndefinedDateTime
  }

  def setHotspotBounds (hs: ViirsHotspot): Unit = {
    hs.bounds = computeHotspotBounds(hs.date, hs.position, hs.scan, hs.track)
  }

  def setSwathWidth (ops: OverpassSeq): Unit = {
    ops.swathWidth = ops.approximateSwathWidth(maxScanAngle)
  }

  // TODO - this is inefficient. Use quaternions or Rodriguez' rotation in ecef
  def computeHotspotBounds (date: DateTime, pos: GeoPosition, scan: Length, track: Length): Seq[GeoPosition] = {
    getTrajectoryPoint( date, pos) match {
      case Some(satPos) =>
        val scanDist = scan / 2
        val trackDist = track / 2

        val scanAngle = GreatCircle.initialBearing(pos, satPos) // from pos to satPos
        val trackAngle: Angle = scanAngle + HalfPi // satellite track (perp to scanAngle)
        val oppoTrackAngle: Angle = trackAngle + Pi // oppositional satellite track

        val pScan0 = GreatCircle.endPos(pos, scanDist, scanAngle) // towards satellite on GC
        val pScan1 = GreatCircle.endPos(pos, scanDist, scanAngle + Pi) // away from satellite on GC

        Seq( // polygon (clockwise, starting in upper left)
          GreatCircle.endPos(pScan0, trackDist, trackAngle),
          GreatCircle.endPos(pScan1, trackDist, trackAngle),
          GreatCircle.endPos(pScan1, trackDist, oppoTrackAngle),
          GreatCircle.endPos(pScan0, trackDist, oppoTrackAngle)
        )

      case None => Seq.empty // no trajectory point - nothing we can do
    }
  }

  // note that date in VIIRS hotspot records is the acquisition time (full minute), i.e. we should compute geometrically and not
  // look up the nearest trajectory point by time (JPSSes are moving fast)
  def getTrajectoryPoint (d: DateTime, p: GeoPosition): Option[GeoPosition] = {
    overpasses.foreach { ops=>
      ops.trajectory.foreach { trj=>
        if (d.isWithin( trj.getFirstDate, trj.getLastDate)) {
          return Some( GreatCircle.crossTrackPoint(p, trj.getFirst.get.position, trj.getLast.get.position))
        }
      }
    }
    None
  }

  def queryBounds: String = { // rectangular hull of overpass bounds: W,S,E,N  (FIRMS api specific)
    var w = Angle.UndefinedAngle
    var s = Angle.UndefinedAngle
    var e = Angle.UndefinedAngle
    var n = Angle.UndefinedAngle

    overpassBounds.foreach { p=>
      val lat = p.lat
      val lon = p.lon

      if (w.isUndefined || lon < w) w = lon
      if (e.isUndefined || lon > e) e = lon
      if (s.isUndefined || lat < s) s = lat
      if (n.isUndefined || lat > n) n = lat
    }

    s"${w.toNormalizedDegrees180},${s.toDegrees},${e.toNormalizedDegrees180},${n.toDegrees}"
  }
}
