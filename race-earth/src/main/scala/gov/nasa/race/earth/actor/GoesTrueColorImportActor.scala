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

import com.typesafe.config.Config
import gov.nasa.race.core.{BusEvent, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.earth.{Gdal2Tiles, ImageTilesAvailable}
import gov.nasa.race.http.{FileRetrieved, HttpFileRetriever, RequestFile}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.common.FileAvailable

import java.io.File
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global


class GOESTrueColorFileRetriever (val config: Config) extends HttpFileRetriever with PeriodicRaceActor {
  val url = config.getString("url")
  val fullDir = new File(System.getProperty("user.dir"), config.getString("file-path")) // where it is saved
  val infoUrl = config.getString("info-url")
  val sat = config.getString("sat")
  Thread.sleep(7000)

  override def defaultTickInterval: FiniteDuration = 5.minutes

  override def onRaceTick(): Unit = {
    info("received RaceTick at")
    RequestGOESFile // self ! RequestFile(url, file, true)
    // http method hget check content length of header
  }

  def RequestGOESFile = {
    val now:DateTime = DateTime.now
    val nowDOY: DateTime = DateTime(now.getYear, now.getDayOfYear, now.getHour, now.getMinute, now.getSecond, now.getMillisecond)
    val nowString: String = nowDOY.format_yMdHms // need to save in format of replay files
    val saveTo: File = new File(fullDir.getPath,sat+"_true_color_c"+nowString+".tif") // need to send full path
    self ! RequestFile(url, saveTo, true)
  }

  def onFirstCall(): Unit = {
    // http get request to the info url
    // parse response of info url
    // get raw date time
    // convert to DateTime
    // get temp tick interval as interval - (current time - tif time)
    // set temp tick interval
    // request first file
  }

  def onSecondCall(): Unit = {
    // reset temp tick interval back to original tick interval
  }
}

//class GoesTrueColorImageImportActor (val config: Config) extends PublishingRaceActor with SubscribingRaceActor {
//
//  val gdal2TilesEnv: File = new File(config.getString("gdal-tiles-env"))
//  val gdal2Tiles: File = new File(config.getString("gdal-tiles"))
//  val tilesCmd = new Gdal2Tiles(gdal2Tiles, Some(gdal2TilesEnv))
//  val outDir: File =  new File(config.getString("output-dir"))
//
//  def handleImageMessage: Receive = {
//    case BusEvent(imageChannel, msg:FileRetrieved, _) => // need to account for replay
//      makeTiles(msg) // Bus event should trigger request by message from goes true color import actor
//  }
//
//  override def handleMessage: PartialFunction[Any, Unit] = handleImageMessage orElse super.handleMessage
//
//  def makeTiles (image: FileRetrieved) = {
//    val inFile: File = image.req.file
//    val sat: String = "sat"
//    val outputPath: File = new File (outDir, image.date)// satellite, date
//    info(s"saving true color wms tiles to $outputPath")
//    val res = tilesCmd.reset()
//      .setInFile(inFile)
//      .setOutFile(outputPath)
//      .setWebViewer("all")
//      .setTileSize(256)
//      .setZoom("7")
//      .exec()
//    res.onComplete{
//        case Success(v) =>
//          info("finished storing image tiles")
//          publish(ImageTilesAvailable(sat, outputPath, image.date))
//        case Failure(x) =>
//          warning(s"creating tiles failed with $x")
//      }
//  }
//
//  def parseSatelliteName(file: File): Option[String] = {
//    // GOES
//    val satellitePattern1 = raw"GOES(\d{2})".r
//    val p1 = satellitePattern1.unanchored
//    val satellitePattern2 = raw"G(\d{2})".r
//    val p2 = satellitePattern2.unanchored
//    file.getName match {
//      case p1(sat) =>
//        val satelliteName: String = "GOES " + sat
//        Some(satelliteName)
//      case p2(sat) =>
//        val satelliteName: String = "GOES " + sat
//        Some(satelliteName)
//      case _ =>
//        None
//    }
//    // JPSS
//  }
//}



