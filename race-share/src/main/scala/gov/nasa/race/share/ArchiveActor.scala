/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.share

import java.io.{File, OutputStream}

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.archive.TaggedStringArchiveWriter
import gov.nasa.race.common.{ConfigurableStreamCreator, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * archiver for ColumnData and ColumnDataChanges
  *
  * TODO - not very efficient, too much copying
  */
class ArchiveActor(val config: Config) extends SubscribingRaceActor
                                        with ContinuousTimeRaceActor with PeriodicRaceActor {

  val dataDir = config.getExistingDir("data-dir")

  var columnData: mutable.Map[String,ColumnData] = mutable.Map.empty
  var changedColumns: mutable.Set[String] = mutable.Set.empty

  val jsonWriter = new JsonWriter()

  val logPath = config.getStringOrElse("change-log", s"$dataDir/changelog")
  val logWriter = new TaggedStringArchiveWriter( createChangeLog(logPath), logPath, 4096)

  override val TickIntervalKey = "save-interval"
  override def defaultTickInterval: FiniteDuration = 60.seconds

  def createChangeLog (logPath: String): OutputStream = {
    val maxLogs = config.getIntOrElse("max-logs", 5)
    FileUtils.rotate(logPath, maxLogs)
    ConfigurableStreamCreator.createOutputStream(config,"change-log", logPath)
  }

  override def onRaceTick(): Unit = saveChangedColumnData

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    logWriter.close()
    saveChangedColumnData
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case BusEvent(_, cdc: ColumnDataChange, _) => logColumnDataChange(cdc)
    case BusEvent(_, cd: ColumnData, _) => updateColumnData(cd)
  }

  def logColumnDataChange (cdc: ColumnDataChange): Unit = {
    val record = jsonWriter.toJson(cdc)
    logWriter.write(simTime, record)
  }

  def updateColumnData (cd: ColumnData): Unit = {
    columnData += (cd.id -> cd)
    changedColumns += cd.id
  }

  def saveChangedColumnData: Unit = {
    changedColumns.foreach { colId =>
      val f = new File(dataDir, s"$colId.json") // TODO - should we use full column paths
      columnData.get(colId) match {
        case Some(cd) =>
          if (!FileUtils.setFileContents(f, jsonWriter.toJson(cd))) {
            error(s"failed to write file: $f")
            // TODO should we try some backup storage here?
          } else {
            info(s"saved file: $f")
          }
        case None => // no data for this column?
          warning(s"no data for changed $colId")
      }
    }
    changedColumns.clear()
  }
}
