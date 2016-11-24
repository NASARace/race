/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.ui

import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceLogger
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{LogConsole, StdConsole}

import scala.swing.{BorderPanel, Orientation, ScrollPane, SplitPane}

/**
  * RaceConsole page for std stream and log output
  */
class ConsolePage (raceConsole: GUIMain, pageConfig: Config) extends BorderPanel {
  val stdConsole = new StdConsole().styled('console)
  val stdioPanel = new BorderPanel {
    layout(stdConsole.createHeader("StdIO ").styled().layoutChildren) = BorderPanel.Position.North
    layout(new ScrollPane(stdConsole).styled()) = BorderPanel.Position.Center
  } styled()

  stdConsole.echo = pageConfig.getBooleanOrElse("echo", false)
  System.setOut(stdConsole.out)
  System.setErr(stdConsole.err)

  val logConsole = new LogConsole().styled('console)
  RaceLogger.logAppender = logConsole
  val logHeader = logConsole.createLogHeader("Logging "){
    case LogConsole.ERROR   => RaceLogger.logController.setLogLevel(Logging.ErrorLevel)
    case LogConsole.WARNING => RaceLogger.logController.setLogLevel(Logging.WarningLevel)
    case LogConsole.INFO    => RaceLogger.logController.setLogLevel(Logging.InfoLevel)
    case LogConsole.DEBUG   => RaceLogger.logController.setLogLevel(Logging.DebugLevel)
  } styled()
  logHeader.layoutChildren

  val logPanel = new BorderPanel {
    layout(logHeader) = BorderPanel.Position.North
    layout(new ScrollPane(logConsole).styled()) = BorderPanel.Position.Center
  } styled()

  val splitPane = new SplitPane(Orientation.Horizontal, stdioPanel, logPanel) {
    oneTouchExpandable = true
    dividerLocation = 150
    resizeWeight = 0.5
  } styled()

  layout(splitPane) = BorderPanel.Position.Center

  def selectedLogLevel = logHeader.selectedLevel
}
