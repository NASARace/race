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

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, MediaTypes}
import com.typesafe.config.Config
import gov.nasa.race
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{ExternalProc, StringJsonPullParser}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth.{Gdal2Tiles, GdalContour, GdalPolygonize, GdalWarp, SmokeAvailable}
import gov.nasa.race.http.{FileRetrieved, HttpActor}
import gov.nasa.race.uom.DateTime

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.sys.process.Process
import scala.util.{Failure, Success}


trait  SmokeSegmentationActor extends PublishingRaceActor with SubscribingRaceActor{
  // unsure what might go here - may omit
}

/**
  * a import actor class which performs image segmentation to get smoke and cloud geojsons
  * @param config
  */

class SmokeSegmentationImportActor(val config: Config) extends SmokeSegmentationActor with HttpActor{
  case class ProcessDataSet (date: DateTime, files: Seq[File])
  case class WarpDataset ()
  case class ContourDataset ()

  val apiPort: String = config.getString("api-port")

  val dataDir: File = new File(config.getString("data-dir"), "tifs")
  val dataDirContours: File = new File(config.getString("data-dir"), "contours")
  val pythonPath: File = new File(config.getString("python-exe"))
  val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe"))
  val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd"))
  val apiProcess = new SmokeSegmentationAPIProcess(pythonPath, Some(apiCwd), apiPath)
  //apiProcess.ignoreOutput

  var runningProc:Process = startAPI // starts the Python server hosting model API and checks if it is available
  var apiAvailable: Boolean = true //false
  val gdalContour:File = new File(config.getString("gdal-contour"))
  //val gdalContour:File = new File(config.getStringOrElse("gdal-contour", getExecutableFile("gdal-contour-prog", "gdal_contour").getPath))
  val gdalWarp: File = new File(config.getString("gdal-warp"))

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    val stopFuture = stopAPI()
    Await.result(stopFuture, 5.seconds)
    stopFuture.onComplete {
      case Success(v) => info ("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    //runningProc.destroy()
    super.onTerminateRaceActor(originator)
  }

  def stopAPI(): Future[Unit] = {
    Future {
      httpRequestStrict(apiPort.replace("/predict", "/stop_server"), HttpMethods.GET) {
        case Success(strictEntity) =>
          info("finished stopping smoke api server")
          apiAvailable = false
        case Failure(x) =>
          warning(s"failed to stop smoke api server $x")
          apiAvailable = false

      }
    }
  }

  def startAPI: Process = {
    val runningProc = apiProcess.customExec()
    Thread.sleep(6000) // bad practice - need to remove?
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture,  Duration.Inf)
    serviceFuture.onComplete {
      case Success(v) => info ("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    runningProc
  }

  def IsServiceAvailable(): Future[Unit]  = { // need future on this - need to wait until it executes to continue in thread
    Future {
      httpRequestStrict(apiPort, HttpMethods.GET) {
        case Success(strictEntity) =>
          info("finished initiating smoke api")
          apiAvailable = true
        case Failure(x) =>
          warning(s"smoke api is not initiated $x")
          apiAvailable = false
      }
    }
  }

  def handleSmokeMessage: Receive = {
    case BusEvent(imageChannel, msg:FileRetrieved, _) => // need to account for replay
      makeRequest(msg) // Bus event should trigger request by message from goes true color import actor
    case pds: ProcessDataSet => handleProcessDataSet(pds) // completed segmentation event should trigger processing the data for visualization
  }

  override def handleMessage: PartialFunction[Any, Unit] = handleSmokeMessage orElse super.handleMessage

  def makeRequest(importedImage: FileRetrieved): Unit = { // flatmap, break up, add thread boundaries for the on completes
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, 3.seconds)
    serviceFuture.onComplete {
      case Success(v) => info ("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    if (apiAvailable) {
      val tiffBytes: Array[Byte] = FileUtils.fileContentsAsBytes(importedImage.req.file).get
      val reqEntity = HttpEntity(MediaTypes.`image/tiff`, tiffBytes) // image/tiff
      val outputFile: File = new File(dataDir.getPath, importedImage.req.file.getName.replace(".tif", "_segmented.tif"))
      httpRequestFileWithTimeout(apiPort, outputFile, 240.seconds, HttpMethods.POST, entity = reqEntity).onComplete {
        case Success(f) =>
          info(s"download complete: $f")
          // warp to reduce resolution
          val warpFuture: Future[ResultValue[File]] = reduceResolution(f)
          warpFuture.onComplete{
            case Success(file) =>
              {
                val contourFuture: Future[ResultValue[File]] = createSmokeCloudContours(file.get, importedImage.date)
                contourFuture.onComplete{ // once contours are completed
                  case Success(c) =>
                    val contourFiles: Seq[File] = Seq(getOutputFile(importedImage.date, "smoke", parseSatelliteName(c.get).get), getOutputFile(importedImage.date, "cloud", parseSatelliteName(c.get).get))
                    info(s"created contour files: $contourFiles")
                    self ! ProcessDataSet(importedImage.date, contourFiles)// process data set by sending two smoke available messages - one with cloud one with smoke data
                  case Failure(x) =>
                    warning(s"failed to create smoke and cloud contour files: $x")
                    println(s"failed to create smoke and cloud contour files: $x")
                }
              }
            case Failure (x) =>
              warning ("resolution reduction failed")
          }
        case Failure(x) =>
          warning(s"download failed: $x")
      }
    }
  }

  def reduceResolution(segmentedFile:File): Future[ResultValue[File]] = {
    val warpCmd = new GdalWarp(gdalWarp)
    val res = warpCmd.reset()
      .setInFile(segmentedFile)
      .setOutFile(new File(segmentedFile.getPath.replace(".tif", "_reduced.tif"))) // this is overwritten if reset called before exec
      .setTargetSize("1100", "466")
      .setResamplingMethod("near")
      .setOverwrite()
      .exec()
    res
  }

  def createSmokeCloudContours(segmentedFile:File, date:DateTime): Future[ResultValue[File]] = {
    val result = for {
      r1 <- createContourFile(segmentedFile, getOutputFile(date, "smoke", parseSatelliteName(segmentedFile).get), 2, 1)
      r2 <- createContourFile(segmentedFile, getOutputFile(date, "cloud", parseSatelliteName(segmentedFile).get), 3, 1)
    } yield (r2)
    result
  }

  def getOutputFile(date: DateTime, scType: String, satellite: String): File = {
    new File(dataDirContours, s"${satellite}_${scType}_${date.format_yMd_Hms_z(('-'))}.geojson")
  }

  def createContourFile(segmentedFile: File, outFile:File, band: Int, interval: Double): Future[ResultValue[File]] = {
    val contourCmd = new GdalContour(gdalContour)
    val res = contourCmd.reset() // try to remove islands, start with binaries
      .setInFile( segmentedFile)
      .setOutFile( outFile)
      .setBand(band)
      .setInterval(interval)
      .setPolygon()
      .setAttrMinName("prob") // in case
      //.ignoreConsoleOutput()
      .exec()//.execSync().asOption
    res
  }

  def handleProcessDataSet(pds: ProcessDataSet): Unit = {
    // publishes data to bus
    publish(SmokeAvailable(parseSatelliteName(pds.files(0)).get, pds.files(0), pds.files(1), "epsg:4326", pds.date))
  }

  def parseSatelliteName(file: File): Option[String] = {
    val satellitePattern1 = raw"GOES(\d{2})".r
    val p1 = satellitePattern1.unanchored
    val satellitePattern2 = raw"G(\d{2})".r
    val p2 = satellitePattern2.unanchored
    file.getName match {
      case p1(sat) =>
        val satelliteName: String = "G" + sat
        Some(satelliteName)
      case p2(sat) =>
        val satelliteName: String = "G" + sat
        Some(satelliteName)
      case _ =>
        None
    }
  }
}

class SmokeSegmentationAPIProcess(val prog: File, override val cwd: Some[File], val apiPath: File) extends ExternalProc[Boolean] {
  if (!prog.isFile) throw new RuntimeException(s"Python executable not found: $prog")
  if (!apiPath.isFile) throw new RuntimeException(s"Smoke Segmentation API run script not found: $apiPath")

  protected override def buildCommand: StringBuilder = {
    args = List(
      s"$apiPath"
    )
    super.buildCommand
  }

  override def getSuccessValue: Boolean = {
    true
  }

  def customExec(): Process = {
    val proc = log match {
      case Some(logger) => Process(buildCommand.toString(), cwd, env: _*).run(logger)
      case None => Process(buildCommand.toString(), cwd, env: _*).run()
    }
    proc
  }
}



