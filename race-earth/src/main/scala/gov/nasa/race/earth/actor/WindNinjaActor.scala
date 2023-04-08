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
import gov.nasa.race.common.{ActorDataAcquisitionThread, DataAcquisitionThread, ExternalProc}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.earth.{VegetationType, WindFieldAvailable, WindNinjaWxModelResults, WindNinjaWxModelSingleRun}
import gov.nasa.race.geo.BoundingBoxGeoFilter
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, SuccessValue, allDefined, earth, ifSome}

import java.io.File
import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.Queue

object WindNinjaArea {
  def fromConfig(conf: Config): WindNinjaArea = {
    val name = conf.getString("name")
    val bounds =  BoundingBoxGeoFilter.fromConfig( conf.getConfig("bounds"))
    val demFile: File = conf.getNonEmptyFile("dem-file")
    val vegetationType: VegetationType = VegetationType.of(conf.getString("vegetation-type")).get // should be computed
    WindNinjaArea(name,bounds,demFile,vegetationType)
  }
}
case class WindNinjaArea (name: String, bounds: BoundingBoxGeoFilter, demFile: File, vegetationType: VegetationType)

/**
 * background thread to run external commands on HrrrFileAvailable/Area queue entries
 * this has to be outside the actor since we run a sequence of external programs on each queue entry
 * since this can consume a lot of memory and CPU we process queue entries one at a time
 */
class WindNinjaThread ( client: ActorRef,
                        queue: BlockingQueue[(HrrrFileAvailable,WindNinjaArea)],
                        outputDir: File,
                        windNinjaCmd: WindNinjaWxModelSingleRun,
                        huvwGridCmd: HuvwCommand,
                        huvwVectorCmd: HuvwCommand
                      ) extends ActorDataAcquisitionThread(client) {

  override def run(): Unit = {
    while (!isDone.get()) {
      val (hrrr,area) = queue.take() // this is blocking
      runWindNinja( hrrr, area)
    }
  }

  private def runWindNinja (hrrr: HrrrFileAvailable, area: WindNinjaArea): Unit = {

    def createGridFile(area: String, hrrr: HrrrFileAvailable, wnResult: WindNinjaWxModelResults): Option[File] = {
      val bd = hrrr.baseDate
      val fd = hrrr.forecastDate
      val hDiff = fd.timeSince(bd).toFullHours
      val outputFile = new File(outputDir, f"wind-grid-$area-${bd.format_yyyyMMdd}-t${bd.getHour}%02dz-${hDiff}%02d.csv.gz")

      huvwGridCmd.reset()
        .setInputFile( wnResult.huvw)
        .setOutputFile( outputFile)
        .ignoreConsoleOutput()
        .execSync().asOption
    }

    def createVectorFile(area: String, hrrr: HrrrFileAvailable, wnResult: WindNinjaWxModelResults): Option[File] = {
      val bd = hrrr.baseDate
      val fd = hrrr.forecastDate
      val hDiff = fd.timeSince(bd).toFullHours
      val outputFile = new File(outputDir, f"wind-vector-$area-${bd.format_yyyyMMdd}-t${bd.getHour}%02dz-${hDiff}%02d.csv.gz")

      huvwVectorCmd.reset()
        .setInputFile( wnResult.huvw)
        .setOutputFile( outputFile)
        .ignoreConsoleOutput()
        .execSync().asOption
    }

    windNinjaCmd.reset()
      .setDemFile(area.demFile)
      .setVegetationType(area.vegetationType)
      .setHrrrForecast(hrrr.file, hrrr.forecastDate)
      .ignoreConsoleOutput()

    info(s"WindNinja processing: ${hrrr.file}")
    windNinjaCmd.execSync() match { // WATCH OUT - begin non-actor thread
      case SuccessValue(wnResult) =>
        ifSome(createGridFile(area.name, hrrr, wnResult)) { file =>
          info(s"publishing ${huvwGridCmd.wfType} file $file")
          sendToClient( WindFieldAvailable(area.name, area.bounds, huvwGridCmd.wfType, huvwGridCmd.wfSrs, hrrr.baseDate, hrrr.forecastDate, file))
        }

        ifSome(createVectorFile(area.name, hrrr, wnResult)) { file =>
          info(s"publishing ${huvwVectorCmd.wfType} file $file")
          sendToClient( WindFieldAvailable(area.name, area.bounds, huvwVectorCmd.wfType, huvwVectorCmd.wfSrs, hrrr.baseDate, hrrr.forecastDate, file))
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
  val windNinjaCmd = new WindNinjaWxModelSingleRun( config.getExecutableFile("windninja-prog", "WindNinja_cli"), outputDir)
  val huvwGridCmd = new HuvwCsvGridCommand( config.getExecutableFile("huvw-grid-prog", "huvw_csv_grid"), outputDir)
  val huvwVectorCmd = new HuvwCsvVectorCommand( config.getExecutableFile("huvw-vector-prog", "huvw_csv_vector"), outputDir)

  protected var queue = new ArrayBlockingQueue[(HrrrFileAvailable, WindNinjaArea)](config.getIntOrElse("queue-size", 256))
  protected val wnThread = new WindNinjaThread(self,queue,outputDir,windNinjaCmd,huvwGridCmd,huvwVectorCmd).setLogging(this)

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

  override def buildCommand: String = {
    args = Seq(inputFile.get.getPath, outputFile.get.getPath)
    super.buildCommand
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