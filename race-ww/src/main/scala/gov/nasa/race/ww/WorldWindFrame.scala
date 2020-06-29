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

package gov.nasa.race.ww

import java.awt.event._
import java.awt.{Color, Cursor, GraphicsEnvironment, Toolkit}
import java.net.URL
import java.util.concurrent.CountDownLatch

import javax.swing._
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing._
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.awt.WorldWindowGLCanvas
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.{Model, WorldWind}

import scala.swing._
import scala.swing.event.{Key, KeyPressed, KeyTyped, MouseClicked, MousePressed}

/**
  * a toplevel frame with a WorldWindGLCanvas
  */
class WorldWindFrame (config: Config, raceView: RaceViewer) extends AppFrame {

  Platform.useScreenMenuBar
  Platform.enableNativePopups
  //Platform.enableLightweightPopups
  Platform.enableFullScreen(this)

  title = config.getStringOrElse("title", "RACE Viewer")

  // menu items we have to update
  private var popupFullScreenMI: CheckMenuItem = _
  private var popupShowPanelsMI: CheckMenuItem = _
  private var mbShowPanelsMI: CheckMenuItem = _

  // this is the potential blocking point, which might run into timeouts
  val wwd = createWorldWindow(config, raceView)

  val worldPanel =  new AWTWrapper(wwd).styled("world")
  val consolePanel = new CollapsiblePanel().styled("console")
  val consoleWrapper = new ScrollPane(consolePanel).styled("verticalAsNeeded")

  wwd.setCursor(loadMapCursor)
  setWwdMenu(config,raceView,wwd)

  val top = new BorderPanel {
    layout(consoleWrapper) = BorderPanel.Position.West
    layout(worldPanel) = BorderPanel.Position.Center
  } styled ("toplevel")
  contents = top

  size = config.getDimensionOrElse("size", (1400, 1000)) // set size no matter what
  if (config.getBooleanOrElse("fullscreen", false)) {
    toggleFullScreen
    popupFullScreenMI.selected = !popupFullScreenMI.selected
  }

  def getInitialEyePosition: Position = {
    // position over center of continental US if nothing specified
    val defLat = 40.34
    val defLon = -98.66
    val defAlt = Meters(6000000)

    val ec = config.getOptionalConfig("eye")
    if (ec.isDefined){
      val e = ec.get
      val alt = e.getLengthOrElse("altitude", defAlt)
      val lat = e.getDoubleOrElse("lat", defLat)
      val lon = e.getDoubleOrElse("lon", defLon)

      Position.fromDegrees(lat,lon,alt.toMeters)
    } else {
      Position.fromDegrees(defLat,defLon,defAlt.toMeters)
    }
  }

  def createWorldWindow (config: Config, raceView: RaceViewer): WorldWindowGLCanvas = {
    val wwd = new WorldWindowGLCanvas with Redrawable
    val view = wwd.getView

    view.setEyePosition(getInitialEyePosition)

    ifSome(config.getOptionalDouble("max-flight-ft")) { d =>
      ifInstanceOf[RaceWWView](view) { v => v.ensureMaxFlightAltitude(Length.feet2Meters(d))}
    }

    if (config.getBooleanOrElse("offline", false)) WorldWind.setOfflineMode(true)

    wwd.setModel(WorldWind.createConfigurationComponent(AVKey.MODEL_CLASS_NAME).asInstanceOf[Model])
    //wwd.getSceneController().setDeepPickEnabled(true)

    wwd
  }

  def loadMapCursor: Cursor = {
    val mapCursor = config.getStringOrElse("mapcursor", "mapcursor-white")
    Images.loadCursor("mapcursor", mapCursor)
  }

  def setWwdMenu(config: Config, raceView: RaceViewer, wwd: WorldWindowGLCanvas) = {
    val showConsole = config.getBooleanOrElse("show-console", true)

    // we can't use scala-swing for menus since wwd is either a native window or a JPanel

    //--- the popup menu
    val popup = new PopupMenu()

    popupFullScreenMI = new CheckMenuItem("").styled()
    popupFullScreenMI.action = scala.swing.Action("fullscreen") { toggleFullScreen }
    popupFullScreenMI.selected = config.getBooleanOrElse("fullscreen", false)
    popup.contents += popupFullScreenMI

    popupShowPanelsMI = new CheckMenuItem("").styled()
    popupShowPanelsMI.action = scala.swing.Action("show console panels"){ showConsolePanels(popupShowPanelsMI.selected) }
    popupShowPanelsMI.selected = true
    popup.contents += popupShowPanelsMI

    listenTo(worldPanel.mouse.clicks, worldPanel.keys)
    reactions += {
      case MousePressed( c, pos, _, _, true) => popup.show(worldPanel, pos.x,pos.y)
      case KeyPressed(_, Key.F, _, _) => //println("@@@ toggle fullscreen")
    }

    //--- the menubar // we don't use one as long as there is just a single item - too much space
    /*
    val menuBar = new JMenuBar
    val showMenu = new JMenu("Show")
    mbShowPanelsMI = new JCheckBoxMenuItem("show console panel", showConsole)
    mbShowPanelsMI.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, ActionEvent.ALT_MASK))
    showMenu.add(mbShowPanelsMI)
    menuBar.add(showMenu)
    peer.setJMenuBar(menuBar)
    */
  }

  def initializePanel (e: PanelEntry) = consolePanel.add( e.name, e.panel, e.tooltip, e.expand)

  /**
    * dynamic view modifications during runtime
    */
  def setPanel (name: String, panel: Component) = consolePanel.set(name, panel)
  def getPanel (name: String): Option[Component] = consolePanel.get(name)

  /**
    * set visibility of all active console panels
    *
    * TODO - GlCanvas does not resize properly on OS X
    */
  def showConsolePanels(setVisible: Boolean) = {
    if (consoleWrapper.visible != setVisible) {
      consoleWrapper.visible = setVisible
      top.revalidate()

      worldPanel.reInit // required for OS X to adapt the GLCanvas size & position

      popupShowPanelsMI.selected = setVisible
      //mbShowPanelsMI.setSelected(setVisible)
    }
  }

  def showConsolePanel (name: String, setVisible: Boolean) = consolePanel.expand(name, setVisible)

  // this is executed in response to clicking the close button on the window frame, prior to disposing the frame itself
  override def closeOperation() = {
    if (!closing) {
      visible = false
      if (raceView.config.getBooleanOrElse("run-control",false)) raceView.requestRaceTermination
    }
  }
}
