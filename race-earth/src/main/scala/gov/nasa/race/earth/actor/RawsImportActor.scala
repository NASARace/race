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

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race.common.{ByteCsvPullParser, FileAvailable}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{ContinuousTimeRaceActor, PublishingRaceActor}
import gov.nasa.race.earth.{RawsParser, WxStation, WxStationAvailable}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.http.HttpActor
import gov.nasa.race.repeat
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Speed.{MetersPerSecond, UsMilesPerHour}
import gov.nasa.race.uom.Temperature.{Celsius, Fahrenheit, Kelvin}
import gov.nasa.race.uom.{Angle, DateTime, Speed, Temperature}
import gov.nasa.race.uom.Time.{Hours, Minutes}
import gov.nasa.race.util.FileUtils

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.util.{Success, Failure => FailureEx}


/**
 * actor that imports RAWS weather reports for configured stations and emits RawsFileAvailable messages
 *
 * fixed: https://mesowest.utah.edu/cgi-bin/droman/download_api2_handler.cgi?output=csv&unit=0&daycalendar=1&hours=1&time=GMT
 * var: &stn={station-id}&day1={day-of-month}&month1={month-of-year%20d}&year1={year%4d}&hour1={end-hour%d}
 *
 * end-hour is next full hour of current time
 * each station is updated once per hour on a fixed minute. What that minute is we have to retrieve from the first report
 */
class RawsImportActor (val config: Config) extends HttpActor with PublishingRaceActor with ContinuousTimeRaceActor {
  case class Register (wxStation: WxStation)

  val minLength: Int = config.getIntOrElse("min-length", 800)
  val marginMinutes: Int = config.getIntOrElse("margin-minutes", 5) // it appears mesowest does have a lag between reported time and updates
  val baseUrl = config.getStringOrElse("url", "https://mesowest.utah.edu/cgi-bin/droman/download_api2_handler.cgi?output=csv&unit=0&daycalendar=1&hours=1&time=GMT")
  val outputDir: File = FileUtils.ensureWritableDir( config.getStringOrElse("directory", "tmp/raws")).get
  val stationIds: Array[String] = config.getStringArray("stations")

  val wxStations: ArrayBuffer[WxStation] = ArrayBuffer.empty
  protected val nProcessed = new AtomicInteger(0)
  protected val scheduled: ArrayBuffer[Cancellable] = ArrayBuffer.empty

  val parser = new RawsParser

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    initWxStations()
    super.onStartRaceActor(originator)
  }

  def handleRawsMessage: Receive = {
    case Register(wx) => registerStation(wx)
    case wx:WxStation => sendStationUpdateRequest(wx,currentSimTime)
  }

  override def handleMessage: Receive = handleRawsMessage orElse super.handleMessage

  //--- internals

  def registerStation(wxStation: WxStation): Unit = {
    wxStations += wxStation
    if (nProcessed.get == stationIds.length) { // once we have all request results start to schedule
      nProcessed.set(0)
      scheduleWxStationUpdates()
    }
  }

  def scheduleWxStationUpdates(): Unit = {
    nProcessed.set(0)
    val nowMin = currentSimTime.getMinute
    val interval = 60.minutes

    wxStations.foreach { wx=>
      val wxMin = wx.updateMinute + marginMinutes // when can we expect a new version
      val delay = if (wxMin >= nowMin) wxMin-nowMin else (60-nowMin + wxMin)
      info(f"schedule hourly update of station '${wx.id}' at :$nowMin%02d in $delay min")
      scheduled += scheduleRecurring( delay.minutes, interval, wx)
    }
  }

  def initWxStations(): Unit = {
    nProcessed.set(0)
    val now = currentSimTime
    stationIds.foreach( id=> sendStationRegisterRequest(id,now))
  }

  def sendStationRegisterRequest (stationId: String, date: DateTime): Unit = {
    val url = buildUrl(stationId,date)
    info(s"requesting RAWS data for '$stationId' at $date")
    httpRequestStrict(url) { // WATCH OUT - this executes in a different thread
      case Success(strictEntity) =>
        nProcessed.incrementAndGet()
        val data = strictEntity.getData().toArray
        if (data.length >= minLength) {
          saveData(stationId,date,data) match {
            case Some(file) =>
              parser.getWxStationFrom(data) match {
                case Some(wx) =>
                  self ! Register(wx)
                  publish( WxStationAvailable(wx, file, date))
                case None => warning(s"failed to obtain WxStation for '$stationId'")
              }
            case _ => warning(s"failed to save report from WxStation: $stationId")
          }

        } else {
          warning(s"no data from RAWS station '$stationId")
        }

      case FailureEx(x) =>
        nProcessed.incrementAndGet()
        warning(s"failed to retrieve RAWS data for '$stationId'")
    }
  }

  def sendStationUpdateRequest (wx: WxStation, date: DateTime): Unit = {
    val url = buildUrl(wx.id,date)
    info(s"requesting RAWS data for '${wx.id}' at $date'")
    httpRequestStrict(url) { // WATCH OUT - this executes in a different thread
      case Success(strictEntity) =>
        val data = strictEntity.getData().toArray
        if (data.length >= minLength) {
          saveData(wx.id, date, data) match {
            case Some(file) => publish(WxStationAvailable(wx, file, date))
            case _ => warning(s"failed to save report from WxStation: ${wx.id}")
          }
        } else {
          warning(s"no data from RAWS station '${wx.id}")
        }
      case FailureEx(x) => warning(s"failed to retrieve RAWS data for '${wx.id}'")
    }
  }

  def buildUrl (stationId: String, date: DateTime): String = {
    val (year,month,day,hour) = (date + Hours(1)).getYMDH
    s"$baseUrl&stn=$stationId&day1=$day&month1=$month&year1=$year&hour1=$hour"
  }

  def buildFileName (stationId: String, day: Int, month: Int, year: Int, hour: Int, min: Int): String = {
    f"wx_station-$stationId-$year%4d$month%02d$day%02d-t$hour%02d$min%02dz.csv"
  }

  def saveData (stationId: String, date: DateTime, data: Array[Byte]): Option[File] = {
    val (year,month,day,hour,min) = date.getYMDHm
    val fname = buildFileName(stationId, day, month, year, hour, min)
    val file = new File(outputDir,fname)
    if (FileUtils.setFileContents(file,data)) Some(file) else None
  }
}
