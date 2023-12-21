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
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ExternalProc
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth.{GdalContour, GdalWarp, SmokeAvailable}
import gov.nasa.race.http.{FileRetrieved, HttpActor}
import gov.nasa.race.uom.DateTime

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.sys.process.Process
import scala.util.{Failure, Success}

class SmokeSegmentationImportActor(val config: Config) extends PublishingRaceActor with SubscribingRaceActor with HttpActor{
  case class ContourDataSet (files: Seq[File], date: DateTime)
  case class SegmentDataset (file: File, date:DateTime)
  case class WarpDataset (file: File, date:DateTime)

  val apiPort: String = config.getString("api-port")

  val dataDir: File = new File(config.getString("data-dir"), "tifs")
  val dataDirContours: File = new File(config.getString("data-dir"), "contours")

  val gdalContour:File = getExecutableFile("gdal-contour-prog", "gdal_contour")
  val gdalWarp: File = getExecutableFile("gdal-warp-prog", "gdalwarp")
  var apiProcess: Option[SmokeSegmentationAPIProcess] = None
  // python server set up
  val startApi: Boolean = config.getBoolean("start-api")
  if (startApi) {
    val pythonPath: File = new File(config.getString("python-exe"))
    val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe"))
    val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd"))
    apiProcess = Some(new SmokeSegmentationAPIProcess(pythonPath, Some(apiCwd), apiPath))
    var runningProc:Process = startAPI // starts the Python server hosting model API and checks if it is available
  }
  //apiProcess.ignoreOutput()


  var apiAvailable:Boolean = false
  val serviceFuture = IsServiceAvailable()
  Await.result(serviceFuture,  Duration.Inf)
  serviceFuture.onComplete {
    case Success(v) => info ("smoke api status confirmed")
    case Failure(x) => warning(s"smoke api status could not be confirmed $x")
  }


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
    val runningProc = apiProcess.get.customExec()
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
    case cds: ContourDataSet =>
      publishContours(cds) // completed segmentation event should trigger processing the data for visualization
    case sds: SegmentDataset =>
      reduceResolution(sds) // segmented images are reduced in resolution for preparation for contour creation
    case wds: WarpDataset =>
      createSmokeCloudContours(wds) // creates contours
  }

  override def handleMessage: PartialFunction[Any, Unit] = handleSmokeMessage orElse super.handleMessage

  def makeRequest(importedImage: FileRetrieved): Unit = {
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
        case Success(f) => // gets response - check for 200 then filter
          println(f)
          info(s"download complete: $f")
          self ! SegmentDataset(f, importedImage.date)
        case Failure(x) =>
          println(x)
          warning(s"download failed: $x")
      }
    }
  }

  def reduceResolution(segmentedData:SegmentDataset): Unit = {
    val warpCmd = new GdalWarp(getExecutableFile("gdalwarp-prog", "gdalwarp"))//gdalWarp)
    val res = warpCmd.reset()
      .setInFile(segmentedData.file)
      .setOutFile(new File(segmentedData.file.getPath.replace(".tif", "_reduced.tif"))) // this is overwritten if reset called before exec
      .setTargetSize("1100", "466")
      .setResamplingMethod("near")
      .setOverwrite()
      .exec()
    res.onComplete {
      case Success(file) =>
        self ! WarpDataset(file.get, segmentedData.date)
      case Failure(x) =>
        warning("resolution reduction failed")
    }
  }

  def createSmokeCloudContours(warpDataset: WarpDataset): Unit = {
    val result = for {
      r1 <- createContourFile(warpDataset.file, getOutputFile(warpDataset.date, "smoke", parseSatelliteName(warpDataset.file).get), 2, 1)
      r2 <- createContourFile(warpDataset.file, getOutputFile(warpDataset.date, "cloud", parseSatelliteName(warpDataset.file).get), 3, 1)
    } yield (r2)
    result.onComplete{ // once contours are completed
      case Success(c) =>
        val contourFiles: Seq[File] = Seq(getOutputFile(warpDataset.date, "smoke", parseSatelliteName(c.get).get), getOutputFile(warpDataset.date, "cloud", parseSatelliteName(c.get).get))
        info(s"created contour files: $contourFiles")
        self ! ContourDataSet(contourFiles,warpDataset.date)// process data set by sending two smoke available messages - one with cloud one with smoke data
      case Failure(x) =>
        warning(s"failed to create smoke and cloud contour files: $x")
    }
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

  def publishContours(cds: ContourDataSet): Unit = {
    // publishes data to bus
    publish(SmokeAvailable(parseSatelliteName(cds.files(0)).get, cds.files(0), cds.files(1), "epsg:4326", cds.date))
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



