/*
 * Copyright (c) 2023, United States Government, as represented by the
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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.FileAvailable
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{ContinuousTimeRaceActor, PublishingRaceActor}
import gov.nasa.race.earth.actor.HrrrFile.outputFileName
import gov.nasa.race.geo.BoundingBoxGeoFilter
import gov.nasa.race.http.HttpActor
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.uom.Time.{Hours, Minutes}
import gov.nasa.race.util.{ArrayUtils, DateTimeUtils, FileUtils}

import java.io.File
import scala.util.{Success, Failure => FailureEx}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.matching.Regex

/**
 * HRRR grib2 file with filename convention
 *    hrrr-{type}-{region}-wrfsfcf-YYYYMMDD-tHHz-HH.grib2
 * eg.
 *    hrrr-tuvc-west-wrfsfcf-20200820-t00z-00.grib2
 */
object HrrrFile {
  val hrrrRE: Regex = raw"hrrr-(.+)-(.+)-wrfsfcf-(\d{4})(\d{2})(\d{2})-t(\d{2})z-(\d{2}).grib2".r

  def outputFileName (hrrrType: String, area: String, date: DateTime, forecastHour: Int): String = {
    f"hrrr-${hrrrType}-${area}-wrfsfcf-${date.format_yyyyMMdd}-t${date.getHour}%02dz-${forecastHour}%02d.grib2"
  }
}

/**
 * the event we publish
 */
case class HrrrFileAvailable (hrrrType: String, area: String, file: File, baseDate: DateTime, forecastDate: DateTime, bounds: BoundingBoxGeoFilter) extends FileAvailable {
  def date = baseDate

  override def toString(): String = file.getName
}

/**
 * actor that periodically retrieves configurable NOAA HRRR weather forecasts
 *
 * NOMADS directory:   https://nomads.ncep.noaa.gov/pub/data/nccf/com/hrrr/prod/hrrr.20230404/conus/
 * NOMADS file:        https://nomads.ncep.noaa.gov/cgi-bin/filter_hrrr_2d.pl?{query-param}
 * query params:       see https://nomads.ncep.noaa.gov/gribfilter.php?ds=hrrr_2d
 */
class HrrrImportActor (val config: Config) extends HttpActor with PublishingRaceActor with ContinuousTimeRaceActor {

  //--- internal messages
  case class SetLatencyTable (latencies: Array[Time])
  object RequestNext
  case class RequestCompleted (file: File)
  case class RequestFailed (file: File, msg: String)

  val hrrrType = config.getString("hrrr-type")
  val area = config.getString("area")
  val bounds = BoundingBoxGeoFilter.fromConfig( config.getConfig("bounds"))

  val forecasts = Math.min(config.getIntOrElse("forecast-steps", 8),24) // number of hourly forecasts for each request
  val reqDelay = config.getDurationTimeOrElse("request-delay", Minutes(1))
  val maxTry = config.getIntOrElse("max-attempts", 3)

  val dirUrl = config.getStringOrElse("dir-url", "https://nomads.ncep.noaa.gov/pub/data/nccf/com/hrrr/prod/hrrr.${yyyyMMdd}/conus/")
  val fileUrl = config.getStringOrElse("file-url", "https://nomads.ncep.noaa.gov/cgi-bin/filter_hrrr_2d.pl")
  val fields = config.getStringListOrElse("fields", Seq("TCDC", "TMP", "UGRD", "VGRD"))
  val levels = config.getStringListOrElse("levels", Seq("lev_2_m_above_ground", "lev_10_m_above_ground", "lev_entire_atmosphere"))

  val staticQuery = filterQuery
  val outputDir: File = FileUtils.ensureWritableDir( config.getStringOrElse("directory", "tmp/hrrr")).get

  val grib2Header = "GRIB".getBytes
  var latencyTable: Array[Time] = null

  var curDate: DateTime = DateTime.UndefinedDateTime  // this is always on the hour
  var curFc: Int = 0 // 0..forecasts-1
  var curTry: Int = 0


  override def onStartRaceActor(originator: ActorRef): Boolean = {
    initLatencyTable()
    super.onStartRaceActor(originator)
  }

  def handleHrrrMessages: Receive = {
    case SetLatencyTable(latencies) =>
      latencyTable = latencies
      setStart()
      requestNext()

    case RequestNext =>
      requestNext()

    case RequestCompleted(file) =>
      publishFileAvailable(file)
      setNext()
      requestNext()

    case RequestFailed (file,msg) =>
      setNextTry()
      requestNext()
  }

  override def handleMessage: Receive = handleHrrrMessages orElse super.handleMessage

  //--- scheduling and file retrieval

  def setStart(): Unit = {
    // this is only called after latencyTable has been initialized
    var d = DateTime.now
    curDate = d.toPrecedingHour
    if (d.getMinute < latencyTable(0).toMinutes) curDate = curDate - Hours(1)
    curFc = 0 // we always start from forecast step 0
    curTry = 0
  }

  def setNext(): Unit = {
    curFc += 1
    if (curFc > forecasts) { // done with curDate
      curFc = 0
      if (curDate < DateTime.now) { // don't wander off too far into the future
        curDate = curDate + Hours(1)
      }
      curTry = 0
    }
  }

  def setNextTry(): Unit = {
    curTry += 1
    if (curTry >= maxTry) {
      if (curFc < forecasts) { // skip this forecast step
        curFc += 1
      } else { // skip this hour completely
        if (curDate < DateTime.now) {
          curDate = curDate + Hours(1)
        }
      }
      curTry = 0
    }
  }

  def requestNext(): Unit = {
    val dt = DateTime.now.timeSince(curDate)
    if (dt < latencyTable(curFc)) { // not yet - schedule
      val dur = latencyTable(curFc) - dt
      info(s"scheduling next file retrieval in ${dur.showHHMMSS}")
      scheduleOnce( dur, RequestNext)

    } else {
      val url = requestUrl(curDate, curFc)
      val file = new File( outputDir, outputFileName(hrrrType, area, curDate, curFc))

      if (FileUtils.ensureWritable(file).isDefined) {
        info(s"requesting $url -> $file")
        httpRequestFile(url,file).onComplete { // watch out - executed in non-actor thread
          case Success(f) =>
            // check if we really got a grib2 file - unfortunately the server responds with an HTML doc if
            // the file is not available yet: "<!DOCTYPE html...title>data file is not present; ..."
            if (FileUtils.checkFileHeader(file, grib2Header)) {
              info(s"download complete: $f")
              self ! RequestCompleted(file)

            } else {
              info(s"not a grib2 file: $f")
              file.delete()
              self ! RequestFailed(file, "not a grib2 file")
            }

          case FailureEx(x) =>
            warning(s"download failed: $x")
            self ! RequestFailed(file, x.getMessage)
        }
      }
    }
  }

  def publishFileAvailable (file: File): Unit = {
    publish( HrrrFileAvailable(hrrrType,area,file,curDate,curDate + Hours(curFc), bounds))
  }

  //--- helpers to build URLs and filenames

  def requestUrl (date: DateTime, forecastHour: Int): String = {
    s"$fileUrl?${fileQuery(date,forecastHour)}&$staticQuery"
  }

  def fileQuery(date: DateTime, forecastHour: Int): String = {
    f"dir=%%2Fhrrr.${date.format_yyyyMMdd}%%2Fconus&file=hrrr.t${date.getHour}%02dz.wrfsfcf${forecastHour}%02d.grib2"
  }

  def filterQuery: String = s"$varQuery&$levelQuery&$regionQuery"

  def varQuery: String = {
    fields.foldLeft(new StringBuilder()) { (sb, v) =>
      if (sb.length() > 0) sb.append("&")
      sb.append("var_")
      sb.append(v)
      sb.append("=on")
    }.toString
  }

  def levelQuery: String = {
    levels.foldLeft(new StringBuilder()) { (sb, v) =>
      if (sb.length() > 0) sb.append("&")
      sb.append(v)
      sb.append("=on")
    }.toString
  }

  def regionQuery: String = {
    val nw = bounds.nw
    val se = bounds.se
    s"subregion=&toplat=${nw.latDeg}&leftlon=${nw.lonDeg}&rightlon=${se.lonDeg}&bottomlat=${se.latDeg}"
  }

  def dirUrl (date: DateTime): String = {
    dirUrl.replace("${yyyyMMdd}",date.format_yyyyMMdd)
  }

  def initLatencyTable(): Unit = {
    // entry lines are of the form:  ...<a href="hrrr.t10z.wrfsfcf08.grib2">hrrr.t10z.wrfsfcf08.grib2</a> 04-Apr-2023 11:07 159M
    val dirEntryRE = """\.grib2">hrrr\.t(\d{2})z.wrfsfcf(\d{2}).grib2</a>\s+(\d+)-(.+)-(\d{4})\s+(\d{2}):(\d{2})\s""".r

    var date = DateTime.now
    if (date.getHour < 12) date = date - Hours(12) // if we don't have enough dir entries yet we use the previous day
    val url = dirUrl(date)

    info(s"requesting reference directory from $url")
    httpRequestStrict(url) { // this executes in a different thread
      case Success(strictEntity) =>
        val dirListing = new String(strictEntity.getData().toArray)

        var fcMax = -1
        val a = new Array[Time](49) // fc -> max-delay [min]
        ArrayUtils.fill(a,Time.UndefinedTime)

        dirEntryRE.findAllMatchIn(dirListing).foreach {
          case dirEntryRE( hour,fch, dd,mon,yyyy,hh,mm) =>
            val fcDate = DateTime( date.getYear, date.getMonthValue, date.getDayOfMonth, hour.toInt, 0,0,0,DateTime.utcId)
            val createDate = DateTime(yyyy.toInt, DateTimeUtils.monthOfYear(mon), dd.toInt, hh.toInt, mm.toInt, 0,0,DateTime.utcId)

            val fc = fch.toInt
            if (fc > fcMax) fcMax = fc
            if (fc < a.length) {
              val dt = createDate.timeSince(fcDate)
              if (dt > a(fc)) a(fc) = dt
            }

          case _ => // ignore
        }

        if (fcMax >= 8) { // we got enough forecast dir entries
          if (reqDelay.isDefined) {
            for (i <- 0 until a.length) a(i) += reqDelay
          }
          self ! SetLatencyTable(a)

        } else {
          warning(s"not enough directory entries in $url")
        }

      case FailureEx(x) =>
        warning(s"failed to retrieve HRRR download directory from $url")
    }
  }
}
