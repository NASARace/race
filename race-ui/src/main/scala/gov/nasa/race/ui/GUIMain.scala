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

import java.io.File

import com.typesafe.config._
import gov.nasa.race._
import gov.nasa.race.config._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceActorSystem
import gov.nasa.race.main.MainBase
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing._
import gov.nasa.race.util.FileUtils._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.swing._
import scala.util.{Failure, Success}


object GUIMain {
  def main (args: Array[String]): Unit = {
    val frame = new GUIMain
    frame.open()
  }
}

/**
  * toplevel RACE console window
  */
class GUIMain extends AppFrame with MainBase {
  val config = loadConfig.getConfigOrElse("race", ConfigFactory.empty)
  title = config.getStringOrElse("title", "RACE")

  //--- our state
  var selectedConfigFile: Option[File] = None
  var ras: Option[RaceActorSystem] = None

  //--- the top row (always shown on top of selected pane)
  val configSelection = new FileSelectionPanel("config:",
                             config.getOptionalString("config-dir"), config.getOptionalString("config-ext"))(f =>
    ifSome(fileContentsAsUTF8String(f)){ text =>
      configEditorPage.text = text
      configEditorPage.modified = false // initial set doesn't count
      runControls.setHot
      selectedConfigFile = Some(f)
    }
  ).styled()

  val runControls = new RunControlPanel {
    setDisabled
    onStart { startUniverse }
    onStop { stopUniverse }
  } styled()

  val simClock = new DigitalClock().styled()

  val header = new GBPanel() {
    val c = new Constraints(gridy = 0, fill = Fill.Horizontal, anchor = Anchor.West, insets = (2, 2, 2, 2))
    layout(configSelection) = c.weightx(1.0)
    layout(runControls) = c.weightx(0)
    layout(simClock) = c
  } styled()


  //--- the tab pages (only one shown at a time)
  val configEditorPage = new ConfigEditorPage(this,pageConfig("editor")).styled()
  configEditorPage.onModify { tabbedPane.pages(0).title = "Config *" }
  configEditorPage.onSave { tabbedPane.pages(0).title = "Config" }

  val consolePage = new ConsolePage(this,pageConfig("console")).styled()
  val environmentPage = new EnvironmentPage(this,pageConfig("environment")).styled()

  val tabbedPane = new TabbedPane {
    pages += new TabbedPane.Page("Config", configEditorPage)
    pages += new TabbedPane.Page("Graph", new BorderPanel().styled())
    pages += new TabbedPane.Page("Environment", environmentPage)
    pages += new TabbedPane.Page("Console", consolePage)
    pages += new TabbedPane.Page("Actors", new BorderPanel().styled())
    pages += new TabbedPane.Page("Channels", new BorderPanel().styled())
    pages += new TabbedPane.Page("VM", new BorderPanel().styled())
  }

  val messageArea = new MessageArea().styled()

  contents = new BorderPanel {
    layout(header) = BorderPanel.Position.North
    layout(tabbedPane) = BorderPanel.Position.Center
    layout(messageArea) = BorderPanel.Position.South
  } styled ('consolePanel)

  size = config.getDimensionOrElse("size", (800,500))


  //--- end ctor

  def infoMessage (msg: String) = messageArea.info(msg)
  def alertMessage (msg: String) = messageArea.alert(msg)
  def clearMessage = messageArea.clear

  def loadConfig = {
    val sysConf = ConfigFactory.load("raceconsole.conf") // this is already resolved
    var f = new File(System.getProperty("user.dir") + "/.race") // try a local .race first
    if (!f.isFile) f = new File(System.getProperty("user.home") + "/.race") // fall back to ~/.race
    if (f.isFile) ConfigFactory.parseFile(f).resolveWith(sysConf).withFallback(sysConf) else sysConf
  }

  def pageConfig(id: String) = config.getConfigOrElse(id, emptyConfig)

  //--- RaceActorSystem execution
  def startUniverse: Boolean = {
    if (selectedConfigFile.isDefined) {
      if (!configEditorPage.modified) {
        infoMessage(s"instantiating universe...")
        Future[Option[RaceActorSystem]] {
          getUniverseConfig.map(new RaceActorSystem(_))
        } onComplete {
          case Success(r) =>
            ras = r
            ras foreach { universe =>
              infoMessage(s"starting universe ${universe.name}...")
              universe.startActors
              infoMessage(s"universe ${universe.name} started")
            }
          case Failure(err) =>
            alertMessage(s"starting universe failed with $err")
            runControls.setStopped
        }
        return true
      } else alertMessage("config file modified - please save before starting RACE")
    } else alertMessage("no config file selected")
    false
  }

  def getUniverseConfig: Option[Config] = {
    selectedConfigFile match {
      case Some(f:File) if f.isFile =>
        var universeConfig = ConfigFactory.load(ConfigFactory.parseFile(f))
        processGlobalConfig(universeConfig)
        if (universeConfig.hasPath("universe")) universeConfig = universeConfig.getConfig("universe")
        universeConfig = processUniverseConfig(universeConfig, 0, Some(consolePage.selectedLogLevel))
        Some(universeConfig)
      case other => None
    }
  }

  def stopUniverse: Boolean = {
    ras foreach { universe =>
      infoMessage(s"shutting down universe ${universe.name}...")
      if (universe.terminateActors) infoMessage(s"universe ${universe.name} terminated")
      else alertMessage(s"universe ${universe.name} failed to terminate")
    }
    true
  }
}
