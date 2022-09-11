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

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.common.Utf8CsvPullParser
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{PublishingRaceActor, RaceContext}
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
import gov.nasa.race.http.HttpActor
import gov.nasa.race.earth.{ViirsHotspot, ViirsHotspotParser, ViirsHotspots}
import gov.nasa.race.space._
import gov.nasa.race.trajectory.Trajectory
import gov.nasa.race.uom.Angle.{Degrees, HalfPi, Pi}
import gov.nasa.race.uom.DateTime.UndefinedDateTime
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Time.{Days, Hours, Minutes, Seconds, UndefinedTime}
import gov.nasa.race.uom.{Angle, DateTime, Length, Time}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, SuccessValue, ifSome, nonEmptyArrayOrElse}

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Success, Failure => FailureEx}

/**
 * import actor for VIIRS active fire data from FIRMS (https://firms.modaps.eosdis.nasa.gov)
 * note there is a single actor for each satellite
 *
 * While this only imports hotspot data it has several benefits over netcdf import from LANCE (https://nrt3.modaps.eosdis.nasa.gov):
 *   - support for RT and URT, i.e. we can depend on calculated overpass times for data availability (~5min after overpass)
 *   - data available through API supporting spatial and temporal queries - no need to scan directories
 *   - only contains hotspots in CSV - no tentative downloads before we know there is fire, and most concise format
 *   - contains ground projection (track/scan) pixel sizes, which together with overpass info let us compute geodetic boundaries
 */
class JpssImportActor(val config: Config) extends PublishingRaceActor with HttpActor with OreKitActor {

  case class ScheduledRequest (date: DateTime, cancellable: Cancellable)
  case class ProcessDataSet (date: DateTime, data: Array[Byte])

  class HotspotParser extends Utf8CsvPullParser with ViirsHotspotParser {

    // TODO - this is inefficient. Use quaternions or Rodriguez' rotation in ecef
    override protected def computeBounds (date: DateTime, pos: GeoPosition, scan: Length, track: Length): Seq[GeoPosition] = {
      getTrajectoryPoint( date, pos) match {
        case Some(satPos) =>
          val scanDist = scan / 2
          val trackDist = track / 2

          val scanAngle = GreatCircle.initialBearing( pos, satPos) // from pos to satPos
          val trackAngle: Angle = scanAngle + HalfPi // satellite track (perp to scanAngle)
          val oppoTrackAngle: Angle = trackAngle + Pi  // oppositional satellite track

          val pScan0 = GreatCircle.endPos( pos, scanDist, scanAngle)      // towards satellite on GC
          val pScan1 = GreatCircle.endPos( pos, scanDist, scanAngle + Pi)  // away from satellite on GC

          Seq(  // polygon (clockwise, starting in upper left)
            GreatCircle.endPos( pScan0, trackDist, trackAngle),
            GreatCircle.endPos( pScan1, trackDist, trackAngle),
            GreatCircle.endPos( pScan1, trackDist, oppoTrackAngle),
            GreatCircle.endPos( pScan0, trackDist, oppoTrackAngle)
          )

        case None => Seq.empty
      }
    }

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
  val satId = config.getInt("satellite") // NPRAD cat ID
  val source = config.getString("source") // e.g. VIIRS_NOAA20_NRT (instrument+satellite+dataset)
  val overpassBounds = config.getGeoPositionArray("region") // polygon of GeoPositions
  val history = config.getFiniteDurationOrElse("history", 1.day) // for initial request
  val maxScanAngle = Degrees( config.getDoubleOrElse("max-scan", 56.2)) // max VIIRS scan angle
  val mapKey = config.getVaultableString("map-key")

  val tleChannel = config.getString("tle-from")
  val tleMaxAge: Time = config.getFiniteDurationOrElse("tle-max-age", 4.hours)
  val requestDelays = nonEmptyArrayOrElse( config.getTimeSeq("request-delay").toArray)( Array( Minutes(5), Hours(2)))

  val url = s"""https://firms.modaps.eosdis.nasa.gov/usfs/api/area/csv/$mapKey/$source/$queryBounds"""
  val firstUrl = s"$url/${history.toDays}"  // used only in first request to obtain history data
  val updateUrl = s"$url/1" // used for updates (unfortunately we can't specify a history < 1day)

  val dataDir = config.getOptionalString("data-dir").flatMap( FileUtils.ensureDir) // if not set data will not be stored in files

  //--- the operational data
  var tle: Option[TLE] = None
  var overpasses: IndexedSeq[OverpassSeq] = IndexedSeq.empty  // computed when we get a new TLE
  var maxOverpassDuration: Time = UndefinedTime               // computed from TLE
  var trajectories: IndexedSeq[Trajectory] = IndexedSeq.empty

  var requestSchedule = mutable.Queue.empty[DateTime] // computed from overpass times and requestDelays
  var pending: Option[ScheduledRequest] = None // do we already have scheduled the next update request

  var lastRequest = DateTime.UndefinedDateTime // when we issued the last request


  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    initOreKit() && super.onReInitializeRaceActor(raceContext, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator) && {
      publish( tleChannel, TleRequest( self, satId, tleMaxAge))
      true
    }
  }

  override def handleMessage: Receive = {
    case newTle: TLE => handleNewTle(newTle)
    case pds: ProcessDataSet => handleProcessDataSet(pds)
  }

  //--- internals

  def satName = tle.flatMap(_.name)

  // note we will get these repetitively, outside the request schedule
  def handleNewTle (newTle: TLE): Unit = {
    if (newTle.catNum == satId) {
      info(s"got new TLE for satellite $satId")

      tle = Some(newTle)
      maxOverpassDuration = newTle.period / 2 // means we don't support regions spanning a whole meridian
      overpasses = getOverpasses(history.toDays)
      trajectories = getOverpassTrajectories
      requestSchedule = getSchedule

      if (isFirstRequest) {
        // this has to be our first message, published once. Note that we first need the TLE so that we know the (optional) name
        publish( OverpassRegion( satId, satName, overpassBounds.toSeq))
      }

      overpasses.foreach( publish)

      scheduleOnce( tleMaxAge){ publish(tleChannel, TleRequest( self, satId, tleMaxAge)) } // schedule next TLE update

      if (isFirstRequest) {
        sendInitialRequest()

      } else { // check if we already have a scheduled update that overlaps with the new schedule, and if so cancel
        for (sr <- pending; next <- nextOverpassDate) { // note this would leave pending updates that have been moved up intact
          if (DateTime.timeBetween( sr.date, next) < Seconds(60)) { // assuming the new schedule is more accurate we cancel pending
            sr.cancellable.cancel()
            pending = None
          }
        }
      }

      scheduleNextUpdateRequest()
    }
  }

  /**
   * parse raw data into ViirsHotspot sequence and partition it according to overpass (end-)times, which are
   * then published as separate ViirsHotspots objects. This is done so that clients can make use of the refinement
   * that happens from URT -> NRT and still keep a time series
   */
  def handleProcessDataSet(pds: ProcessDataSet): Unit = {
    val parser = new HotspotParser
    val hotspots = parser.parse(pds.data)
    if (hotspots.nonEmpty) {
      // publish each overpass segment separately
      ViirsHotspots.partition(satId, source, hotspots, maxOverpassDuration, overpasses.map(_.lastDate)).foreach(publish)
    }
  }

  @inline def isFirstRequest: Boolean = lastRequest.isUndefined

  def nextOverpassDate: Option[DateTime] = {
    val now = DateTime.now
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

  def sendInitialRequest(): Unit = {
    // only request this now if we aren't close to the next scheduled request
    if (requestSchedule.isEmpty || requestSchedule.front.timeFromNow > Minutes(2)) {
      requestDataSet(firstUrl, DateTime.now)
    } else {
      info(s"initial request postponed to next overpass")
    }
  }

  def scheduleNextUpdateRequest(): Unit = {
    if (requestSchedule.nonEmpty) {
      val nextRequestDate = requestSchedule.dequeue()
      val url = if (lastRequest.isDefined) updateUrl else firstUrl
      val t = nextRequestDate.timeFromNow

      if (t > Seconds(60)) {
        info(s"next update scheduled at: $nextRequestDate")
        pending = Some(
          ScheduledRequest( nextRequestDate, scheduleOnce(t){
            pending = None
            requestDataSet(url, nextRequestDate)
            scheduleNextUpdateRequest()
          })
        )

      } else { // we already passed it
        requestDataSet( url, nextRequestDate)
        scheduleNextUpdateRequest()
      }
    }
  }

  def getOverpasses(backDays: Long): IndexedSeq[OverpassSeq] = {
    tle match {
      case Some(tle) =>
        val startDate = DateTime.now - Days(backDays)
        val dur = Days(backDays + 1) // compute overpasses from history start to next 24h
        OreKit.computeOverpassPeriods( tle, startDate, dur, overpassBounds, maxScanAngle).toIndexedSeq

      case None =>
        warning(s"computing overpasses for sat $satId failed (no TLE)")
        IndexedSeq.empty
    }
  }

  // TODO - we should check TLE epoch here (history might be too long for current)
  def getOverpassTrajectories: IndexedSeq[Trajectory] = {
    var trjs = new ArrayBuffer[Trajectory]( overpasses.size)
    overpasses.foreach { os=>
      // hotspot time is in rounded minutes
      val start = os.firstDate.toPrecedingMinute - Minutes(1)
      val end = os.lastDate.toFollowingMinute + Minutes(1)
      trjs += OreKit.computeOrbit( tle.get, start, Minutes(1), end)
    }
    trjs.toIndexedSeq
  }

  // note that date in VIIRS hotspot records is the acquisition time (full minute), i.e. we should compute geometrically and not
  // look up the nearest trajectory point by time (JPSSes are moving fast)
  def getTrajectoryPoint (d: DateTime, p: GeoPosition): Option[GeoPosition] = {
    for (
      trj <- trajectories.find( t=> d.isWithin(t.getFirstDate, t.getLastDate));
      p0 <- trj.getFirst;
      p1 <- trj.getLast
    ) yield GreatCircle.crossTrackPoint(p, p0.position, p1.position)
  }

  def requestDataSet(uri: String, requestDate: DateTime): Unit = {
    lastRequest = requestDate

    info(s"requesting data at $requestDate from $uri")
    httpGetRequestStrict(uri).onComplete { // NOTE - this is executed in a non-actor thread
      case Success(strictEntity) =>
        val data = strictEntity.getData().toArray
        storeData(requestDate, data) // do IO here since it might externally block
        self ! ProcessDataSet(requestDate, data) // move back into actor thread to avoid sync

      case FailureEx(x) =>
        warning(s"retrieving data set from $uri failed with $x")
    }
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

  //
  def getSchedule: mutable.Queue[DateTime] = {
    val a = ArrayBuffer.empty[DateTime]
    val now = DateTime.now // TODO should we use simTime?
    val delta = Minutes(15)

    requestDelays.foreach { t =>
      overpasses.foreach { ops =>
        val d = ops.lastDate + t
        if (d > now) {
          val i = a.indexWhere(_ > d)
          if (i < 0) { // no later date yet
            if (a.isEmpty || DateTime.timeBetween(a.last, d) > delta) a.append(d) // note this never skips the URT updates
          } else {
            if ((DateTime.timeBetween(a(i), d) > delta) && ((i > 0) && DateTime.timeBetween(a(i-1), d) > delta)) a.insert(i,d)
          }
        }
      }
    }

    info(s"""set schedule: ${a.mkString("[", ", ", "]")}""")
    mutable.Queue.from(a)
  }

  def storeData (date: DateTime, data: Array[Byte]): Unit = {
    ifSome(dataDir) { dir=>
      val fname = s"${source}_${date.format_yMd_Hms_z('-')}.csv"
      val file = new File( dir,fname)
      info(s"saving data to $file")
      FileUtils.setFileContents( file, data)
    }
  }
}

