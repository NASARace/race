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
import gov.nasa.race.common.{ActorDataAcquisitionThread, ExternalProc}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.earth.{GdalContour, GdalWarp, VegetationType, WindFieldAvailable, WindNinjaWxModelResults, WindNinjaSingleRun}
import gov.nasa.race.geo.BoundingBoxGeoFilter
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, SuccessValue, allDefined, ifSome}

import java.io.File
import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}

object WindNinjaArea {
  def fromConfig(conf: Config): WindNinjaArea = {
    val name = conf.getString("name")
    val bounds =  BoundingBoxGeoFilter.fromConfig( conf.getConfig("bounds"))
    val demFile: File = conf.getNonEmptyFile("dem-file")
    val vegetationType: VegetationType = VegetationType.of(conf.getString("vegetation-type")).get // should be computed
    val meshResolution: Length = conf.getLengthOrElse("mesh-resolution", Meters(250))
    WindNinjaArea(name,bounds,demFile,vegetationType,meshResolution)
  }
}
case class WindNinjaArea (name: String, bounds: BoundingBoxGeoFilter, demFile: File, vegetationType: VegetationType, meshResolution: Length)

/**
 * background thread to run external commands on HrrrFileAvailable/Area queue entries
 * this has to be outside the actor since we run a sequence of external programs on each queue entry
 * since this can consume a lot of memory and CPU we process queue entries one at a time
 */
class WindNinjaThread (client: ActorRef,
                       queue: BlockingQueue[(HrrrFileAvailable,WindNinjaArea)],
                       outputDir: File,
                       windNinjaCmd: WindNinjaSingleRun,
                       huvwGridCmd: HuvwCommand,
                       huvwVectorCmd: HuvwCommand,
                       warpCmd: GdalWarp,
                       contourCmd: GdalContour
                      ) extends ActorDataAcquisitionThread(client) {

  override def run(): Unit = {
    while (!isDone.get()) {
      val (hrrr,area) = queue.take() // this is blocking
      runWindNinja( hrrr, area)
    }
  }

  def getOutputFile (area: String, hrrr: HrrrFileAvailable, prefix: String, ext: String): File = {
    val bd = hrrr.baseDate
    val fd = hrrr.forecastDate
    val hDiff = fd.timeSince(bd).toFullHours
    new File( outputDir, f"$prefix-$area-${bd.format_yyyyMMdd}-t${bd.getHour}%02dz-${hDiff}%02d.$ext")
  }

  private def runWindNinja (hrrr: HrrrFileAvailable, area: WindNinjaArea): Unit = {

    def createGridFile(area: String, hrrr: HrrrFileAvailable, wnResult: WindNinjaWxModelResults): Option[File] = {
      huvwGridCmd.reset()
        .setInputFile( wnResult.huvw)
        .setOutputFile( getOutputFile(area, hrrr, "wn-hrrr-grid", "csv.gz"))
        .ignoreConsoleOutput()
        .execSync().asOption
    }

    def createVectorFile(area: String, hrrr: HrrrFileAvailable, wnResult: WindNinjaWxModelResults): Option[File] = {
      huvwVectorCmd.reset()
        .setInputFile( wnResult.huvw)
        .setOutputFile( getOutputFile(area, hrrr, "wn-hrrr-vector", "csv.gz"))
        .ignoreConsoleOutput()
        .execSync().asOption
    }

    def createContourFile (area: String, hrrr: HrrrFileAvailable, wnResult: WindNinjaWxModelResults): Option[File] = {
      val huvw = wnResult.huvw
      val geoHuvw = new File(outputDir, s"${FileUtils.getBaseName(huvw)}_4326.${FileUtils.getGzExtension(huvw)}")

      // convert wnResult.huvw into geographic grid
      val res = warpCmd.reset()
        .setTargetSrs("EPSG:4326")
        .setInFile( huvw)
        .setOutFile( geoHuvw)
        .ignoreConsoleOutput()
        .execSync().asOption

      res.flatMap { inFile =>
        contourCmd.reset()
          .setInFile( inFile)
          .setOutFile( getOutputFile(area, hrrr, "wn-hrrr-contour", "json"))
          .setBand(5)
          .setInterval(5.0)
          //.setAttrName("spd")
          .setPolygon()
          .setAttrMinName("spd") // in case
          .ignoreConsoleOutput()
          .execSync().asOption
      }
    }

    windNinjaCmd.reset()
      .setDemFile(area.demFile)
      .setVegetationType(area.vegetationType)
      .setMeshResolution(area.meshResolution)
      .setHrrrForecast(hrrr.file, hrrr.forecastDate)
      .ignoreConsoleOutput()

    info(s"WindNinja processing: ${hrrr.file}")
    windNinjaCmd.execSync() match { // WATCH OUT - begin non-actor thread
      case SuccessValue(wnResult) =>
        ifSome(createGridFile(area.name, hrrr, wnResult)) { file =>
          info(s"publishing ${huvwGridCmd.wfType} file $file")
          sendToClient( WindFieldAvailable(area.name, area.bounds, huvwGridCmd.wfType, huvwGridCmd.wfSrs, "hrrr", hrrr.baseDate, hrrr.forecastDate, file))
        }

        ifSome(createVectorFile(area.name, hrrr, wnResult)) { file =>
          info(s"publishing ${huvwVectorCmd.wfType} file $file")
          sendToClient( WindFieldAvailable(area.name, area.bounds, huvwVectorCmd.wfType, huvwVectorCmd.wfSrs, "hrrr", hrrr.baseDate, hrrr.forecastDate, file))
        }

        ifSome(createContourFile(area.name, hrrr, wnResult)) { file =>
          info(s"publishing contour file $file")
          sendToClient( WindFieldAvailable( area.name, area.bounds, "contour", "epsg:4326", "hrrr", hrrr.baseDate, hrrr.forecastDate, file))
        }

      case Failure(msg) =>
        warning(s"WindNinja execution failed: $msg")
    } // end of non-actor thread
  }
}

/**
 * actor that listens on a channel with HrrrFileAvailable events, runs WindNinja on respective input
 * files and publishes WindNinjaFileAvailable events generated from its results
 *
 * All files are processed in the order in which they are received. All programs used to process HRRR files
 * are executed outside the actor thread. The order in which WindNinjaFileAvailable events are published corresponds
 * to the order in which respective HRRR files are received
 */
class WindNinjaActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  val areas: Seq[WindNinjaArea] = config.getConfigSeq("areas").map(WindNinjaArea.fromConfig)
  val outputDir: File = FileUtils.ensureWritableDir(config.getStringOrElse("directory", "tmp/windninja")).get

  //--- external progs we use
  val windNinjaCmd = new WindNinjaSingleRun( getExecutableFile("windninja-prog", "WindNinja_cli"), outputDir)
  val huvwGridCmd = new HuvwCsvGridCommand( getExecutableFile("huvw-grid-prog", "huvw_csv_grid"), outputDir)
  val huvwVectorCmd = new HuvwCsvVectorCommand( getExecutableFile("huvw-vector-prog", "huvw_csv_vector"), outputDir)
  val warpCmd = new GdalWarp( getExecutableFile("gdalwarp-prog", "gdalwarp"))
  val contourCmd = new GdalContour(  getExecutableFile("gdal-contour-prog", "gdal_contour"))

  protected var queue = new ArrayBlockingQueue[(HrrrFileAvailable, WindNinjaArea)](config.getIntOrElse("queue-size", 256))
  protected val wnThread = new WindNinjaThread(self,queue,outputDir,windNinjaCmd,huvwGridCmd,huvwVectorCmd,warpCmd,contourCmd).setLogging(this)

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    wnThread.start()
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    wnThread.terminate()
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = handleWindNinjaMessages orElse super.handleMessage

  def handleWindNinjaMessages: Receive = {
    case BusEvent(_, hrrr: HrrrFileAvailable, _) =>
      areas.foreach { area=>
        if (!queue.offer( (hrrr,area))) {
          warning(s"queue full")
        }
      }

    case wfa: WindFieldAvailable => publish(wfa)
  }
}


/**
 * command to translate WindNinja huvw*.tif datasets into RACE specific formats
 */
trait HuvwCommand extends ExternalProc[File] {
  if (!prog.isFile || !prog.canExecute) throw new RuntimeException(s"executable not found: $prog")

  protected var inputFile: Option[File] = None
  protected var outputFile: Option[File] = None

  override def reset(): this.type = {
    super.reset()
    inputFile = None
    this
  }

  override def canRun: Boolean = allDefined( inputFile, outputFile)

  def setInputFile (file: File): this.type = {
    inputFile = Some(file)
    this
  }

  def setOutputFile (file: File): this.type = {
    outputFile = Some(file)
    this
  }

  override def buildCommand: StringBuilder = {
    super.buildCommand
      .append(' ').append( inputFile.get.getPath)
      .append(' ').append( outputFile.get.getPath)
  }

  def getSuccessValue: File = {
    if (!outputFile.isDefined || !outputFile.get.isFile) throw new RuntimeException(s"output file not found: $outputFile")
    outputFile.get
  }

  def wfSrs: String // spatial reference system for generated output
  def wfType: String // wind field type (vector, grid or contour)
}

/**
 * HuvwCommand to create compressed huvw CSV grid files with geographic SRS (epsg:4326)
 */
class HuvwCsvGridCommand (val prog: File, val outputPath: File) extends HuvwCommand {
  def wfSrs = "epsg:4326"
  def wfType = "grid"
}

/**
 * HuvwCommand to create comressed huvw CSV vector files with ECEF coordinates (epsg:4978)
 */
class HuvwCsvVectorCommand (val prog: File, val outputPath: File) extends HuvwCommand {
  def wfSrs = "epsg:4978"
  def wfType = "vector"
}