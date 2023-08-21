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
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.MediaTypes.`image/tiff`
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, MediaTypes}
import com.typesafe.config.Config
import gov.nasa.race
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.common.{ExternalProc, JsonWriter, StringJsonPullParser}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth.{Gdal2Tiles, GdalContour, GdalPolygonize, GdalWarp, SmokeAvailable}
import gov.nasa.race.http.{FileRequestFailed, FileRetrieved, HttpActor}
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

class SmokeSegmentationImportActor(val config: Config) extends SmokeSegmentationActor with HttpActor{
  case class ProcessDataSet (date: DateTime, files: Seq[File])

  val apiPort: String = config.getString("api-port")

  val dataDir: File = new File(config.getString("data-dir"), "tifs")//.flatMap(FileUtils.ensureDir) // if not set data will not be stored in files
  val dataDirContours: File = new File(config.getString("data-dir"), "contours")
  val pythonPath: File = new File(config.getString("python-exe"))
  val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe"))
  val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd"))
  val apiProcess = new SmokeSegmentationAPIProcess(pythonPath, Some(apiCwd), apiPath)
  //apiProcess.ignoreOutput

  var runningProc:Process = startAPI // starts the Python server hosting model API and checks if it is available
  var apiAvailable: Boolean = true //false
  val gdalContour:File = new File(config.getString("gdal-contour"))
  val gdalPolygonize:File = new File(config.getString("gdal-polygonize"))
  val gdalPolygonizeEnv:File = new File(config.getString("gdal-polygonize-env"))
  //val gdalContour:File = new File(config.getStringOrElse("gdal-contour", getExecutableFile("gdal-contour-prog", "gdal_contour").getPath))
  val contourCmd = new GdalContour(gdalContour)
  val polygonCmd = new GdalPolygonize(gdalPolygonize, Some(gdalPolygonizeEnv))

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    runningProc.destroy()
    super.onTerminateRaceActor(originator)
  }

  def startAPI: Process = {
    val runningProc = apiProcess.customExec()
    Thread.sleep(6000) // bad practice - need to remove?
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, 3.seconds)
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
    case pds: ProcessDataSet => handleProcessDataSet(pds) // completed segmentation event should trigger processing the data for visualization - currently undefined
  }
  override def handleMessage = handleSmokeMessage orElse super.handleMessage

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
      httpRequestFile(apiPort, outputFile, HttpMethods.POST, entity = reqEntity).onComplete {
        case Success(f) =>
          info(s"download complete: $f")
          // send message with file retrieval to process data set
          val contourFuture: Future[Any] = createSmokeCloudContours(f, importedImage.date)//createSmokeCloudContours(f, importedImage.date)
          contourFuture.onComplete{
            case Success(c) =>
              println(c)
              val contourFiles: Seq[File] = Seq(getOutputFile(importedImage.date, "smoke", parseSatelliteName(f).get), getOutputFile(importedImage.date, "cloud", parseSatelliteName(f).get))
              info(s"created contour files: $contourFiles")
              self ! ProcessDataSet(importedImage.date, contourFiles)// process data set by sending two smoke available messages - one with cloud one with smoke data
            case Failure(x) =>
              warning(s"failed to create smoke and cloud contour files: $x")
              println(s"failed to create smoke and cloud contour files: $x")
          }
        case Failure(x) =>
          warning(s"download failed: $x")
      }
    }
  }

  def createSmokeCloudContours(segmentedFile:File, date:DateTime): Future[Any] = {
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

  def createSmokeCloudPolygons(segmentedFile:File, date:DateTime): Future[ResultValue[File]] = {
    val result = for {
      r1 <- createPolygonFile(segmentedFile, getOutputFile(date, "smoke", parseSatelliteName(segmentedFile).get), 2, "smoke")
      r2 <- createPolygonFile(segmentedFile, getOutputFile(date, "cloud", parseSatelliteName(segmentedFile).get), 3, "cloud")
    } yield (r2)
    result
  }

  def createPolygonFile(segmentedFile: File, outFile: File, band: Int, scType: String): Future[ResultValue[File]] = {
    val field: String = "prob"
    val res = polygonCmd.reset() // try to remove islands, start with binaries
      .setInFile( segmentedFile)
      .setOutFile( outFile)
      .setBand(band)
      .setOutputLayer(scType)
      .setOutputField(field)
      .setOverwrite()
      .setConnectedness()
      .exec()//.execSync().asOption
    res
  }

  def handleProcessDataSet(pds: ProcessDataSet): Unit = {
    info("in handle process data set") // Undefined now - will need to process for visualization needs
    //smoke - parsing output file which does not contain the satellite name - add to name
    publish(SmokeAvailable(parseSatelliteName(pds.files(0)).get, pds.files(0), "smoke", "epsg:4326", pds.date))
    //add satellite parser from file download
    publish(SmokeAvailable(parseSatelliteName(pds.files(1)).get, pds.files(1), "cloud", "epsg:4326", pds.date))
  }

  def parseSatelliteName(file: File): Option[String] = {
    val satellitePattern1 = raw"GOES(\d{2})".r // raw".*_c(\d{4})(\d{3})(\d{2})(\d{2})(\d{2})(\d)\.tif".r
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

class SmokeSegmentationImageryActor(val config: Config) extends SmokeSegmentationActor with HttpActor {
  case class ProcessDataSet(date: DateTime, files: Seq[File])

  val apiPort: String = config.getString("api-port")

  val dataDir: File = new File(new File(config.getString("data-dir"), "tifs").getPath, "combined") //.flatMap(FileUtils.ensureDir) // if not set data will not be stored in files
  val dataDirTifs: File = new File(config.getString("data-dir"), "tifs")
  val dataDirTiles: File = new File(config.getString("data-dir"), "tiles")
  val pythonPath: File = new File(config.getString("python-exe"))
  val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe"))
  val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd"))
  val apiProcess = new SmokeSegmentationAPIProcess(pythonPath, Some(apiCwd), apiPath)
  //apiProcess.ignoreOutput

  var runningProc: Process = startAPI // starts the Python server hosting model API and checks if it is available
  var apiAvailable: Boolean = true //false
  val gdal2TilesEnv: File = new File(config.getString("gdal-tiles-env"))
  val gdal2Tiles: File = new File(config.getString("gdal-tiles"))
  val tilesCmd = new Gdal2Tiles(gdal2Tiles, Some(gdal2TilesEnv))

  val gdalWarp: File = new File(config.getString("gdal-warp"))
  val warpCmd = new GdalWarp(gdalWarp)

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    runningProc.destroy()
    super.onTerminateRaceActor(originator)
  }

  def startAPI: Process = {
    val runningProc = apiProcess.customExec()
    Thread.sleep(6000) // bad practice - need to remove?
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, 3.seconds)
    serviceFuture.onComplete {
      case Success(v) => info("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    runningProc
  }

  def IsServiceAvailable(): Future[Unit] = { // need future on this - need to wait until it executes to continue in thread
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
    case BusEvent(imageChannel, msg: FileRetrieved, _) => // need to account for replay
      makeRequestImagery(msg) // Bus event should trigger request by message from goes true color import actor
    case pds: ProcessDataSet => handleProcessDataSet(pds) // completed segmentation event should trigger processing the data for visualization - currently undefined
  }

  override def handleMessage = handleSmokeMessage orElse super.handleMessage

  def makeRequestImagery(importedImage: FileRetrieved): Unit = {
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, 3.seconds)
    serviceFuture.onComplete {
      case Success(v) => info ("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    println(apiAvailable)
    if (apiAvailable) {
      val tiffBytes: Array[Byte] = FileUtils.fileContentsAsBytes(importedImage.req.file).get
      val reqEntity = HttpEntity(MediaTypes.`image/tiff`, tiffBytes) // image/tiff
      val outputFile: File = new File(dataDir.getPath, importedImage.req.file.getName.replace(".tif", "_segmented.tif"))
      httpRequestFile(apiPort, outputFile, HttpMethods.POST, entity = reqEntity).onComplete {
        case Success(s) =>
          info(s"download complete: $s")
          val f: Future[(ResultValue[File], ResultValue[File])] = createSmokeCloudTifs(outputFile, importedImage.date)
          Await.result(f, Duration.Inf) // not in actor thread, add comment to enter and exit thread, could use messages
          f.onComplete {
            case Success(v) =>
              println("warped")
              info("finished warping individual smoke and cloud tifs")
              val outputFiles: Seq[File] = Seq(getOutputFile(importedImage.date, "smoke", parseSatelliteName(outputFile).get), getOutputFile(importedImage.date, "cloud", parseSatelliteName(outputFile).get))
              val f2: Future[(Any, Any)] = storeData(importedImage.date, outputFiles)
              Await.result(f2, Duration.Inf) // not in actor thread, add comment to enter and exit thread, could use messages
              f2.onComplete {
                case Success(v2) =>
                  info("finished storing smoke and cloud tiles")
                  self ! ProcessDataSet(importedImage.date, outputFiles) // move back into actor thread to avoid sync, ! sends the message
                case Failure(te) =>
                  te.printStackTrace
              }
            case Failure(e) =>
              e.printStackTrace
          }
        case Failure(x) =>
          warning(s"retrieving data set failed with $x")
      }
    }
  }

  def getSingleBandTif( inFile:File, outFile: File, band:Int): Future[ResultValue[File]] = {
    val res1 = warpCmd.reset()
      .setInFile(inFile)
      .setOutFile(outFile)
      .setWarpBand(band.toString)
      .setTargetSrs("EPSG:3857")
      .setResamplingMethod("bilinear")
      .exec()
  res1
  }

  def createSmokeCloudTifs(segmentedFile:File, date:DateTime): Future[(ResultValue[File], ResultValue[File])] = {
    val result = for {
      r1 <- getSingleBandTif(segmentedFile, getOutputFile(date, "smoke", parseSatelliteName(segmentedFile).get), 2)
      r2 <- getSingleBandTif(segmentedFile, getOutputFile(date, "cloud", parseSatelliteName(segmentedFile).get), 3)
    } yield (r1, r2)
    result
  }

  def getOutputFile(date: DateTime, scType: String, satellite: String): File = {
    new File(new File(dataDirTifs, scType).getPath, s"${satellite}_${scType}_${date.format_yMd_Hms_z(('-'))}")
  }

  def getOutputFileLocations(data: String): ArrayBuffer[File] = {
    val p = new StringJsonPullParser
    p.initialize(data)
    val _out_ = asc("output")
    val stringSeq = p.readNextObject {
      val stringSeq = p.readNextStringArrayMemberInto(_out_, ArrayBuffer.empty[String])
      assert( stringSeq.size == 2)
      stringSeq
    }
    val fileSeq = stringSeq.map { s =>
      new File(s"$s")
    }
    fileSeq
  }


  def createTiles(outputPath: File, inFile: File): Future[race.ResultValue[File]] = {
    info(s"saving smoke/cloud wms tiles to $outputPath")
    val res = tilesCmd.reset()
      .setInFile(inFile)
      .setOutFile(outputPath)
      .setWebViewer("all")
      .setTileSize(256)
      .setZoom("7")
      .exec()
    res
  }

  def getTileOutputDir(inFile:File, scType:String): File = {
    new File (new File(dataDirTiles, scType).getPath, inFile.getName.replace(".tif", ""))
  }

  def storeData (date: DateTime, data: Seq[File]): Future[(Any, Any)] = { // store tiles
    info(s"tilling smoke and cloud tifs from $date")
    val tiff1: File = data(0)
    val outputFile1 = getTileOutputDir(tiff1, "smoke")
    val tiles1Future = createTiles(outputFile1, tiff1)
    val tiff2: File = data(1)
    val outputFile2 = getTileOutputDir(tiff2, "cloud")
    val tiles2Future = createTiles(outputFile2, tiff2)
    val result = for {
      r1 <- tiles1Future
      r2 <- tiles2Future
    } yield (r1, r2)
    result
  }

  def handleProcessDataSet(pds: ProcessDataSet): Unit = {
    info("in handle process data set") // Undefined now - will need to process for visualization needs
    //smoke - parsing output file which does not contain the satellite name - add to name
    publish(SmokeAvailable(parseSatelliteName(pds.files(0)).get, pds.files(0), "smoke", "epsg:4326", pds.date))
    //add satellite parser from file download
    publish(SmokeAvailable(parseSatelliteName(pds.files(1)).get, pds.files(1), "cloud", "epsg:4326", pds.date))
  }

  def parseSatelliteName(file: File): Option[String] = {
    val satellitePattern1 = raw"GOES(\d{2})".r // raw".*_c(\d{4})(\d{3})(\d{2})(\d{2})(\d{2})(\d)\.tif".r
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



