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
import java.awt.{Color, Cursor, Toolkit}
import java.net.URL
import java.util.concurrent.CountDownLatch
import javax.swing._

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.Length
import gov.nasa.race._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing._
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.awt.WorldWindowGLCanvas
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.{Model, WorldWind}

import scala.swing._

/**
  * a toplevel frame with a WorldWindGLCanvas
  */
class WorldWindFrame (config: Config, raceView: RaceView) extends AppFrame {

  Platform.useScreenMenuBar
  Platform.enableNativePopups
  Platform.enableFullScreen(this)

  title = config.getStringOrElse("title", "RACE Viewer")

  // menu items we have to update
  private var popupShowPanelsMI: JMenuItem = _
  private var mbShowPanelsMI: JMenuItem = _

  // this is the potential blocking point, which might run into timeouts
  val wwd = createWorldWindow(config, raceView)

  val worldPanel =  new AWTWrapper(wwd).styled('world)
  val consolePanel = new CollapsiblePanel().styled('console)
  val consoleWrapper = new ScrollPane(consolePanel).styled('verticalAsNeeded)

  wwd.setCursor(loadMapCursor)

  val top = new BorderPanel {
    layout(consoleWrapper) = BorderPanel.Position.West
    layout(worldPanel) = BorderPanel.Position.Center
  } styled ('toplevel)
  contents = top

  size = config.getDimensionOrElse("size", (1400, 1000)) // set size no matter what
  if (config.getBooleanOrElse("fullscreen", false)) {
    Platform.requestFullScreen(this)
  }

  def createWorldWindow (config: Config, raceView: RaceView): WorldWindowGLCanvas = {
    val wwd = new WorldWindowGLCanvas with Redrawable

    ifSome(config.getOptionalConfig("eye")) { e => // if we don't have an 'eye' config we default to WWJs
      val alt = Length.feet2Meters(e.getDoubleOrElse("altitude-ft", 1.65e7))
      val eyePos = Position.fromDegrees(e.getDoubleOrElse("lat",40.34), e.getDoubleOrElse("lon",-98.66), alt)

      val view = wwd.getView
      view.setEyePosition(eyePos)
      ifSome(config.getOptionalDouble("max-flight-ft")) { d =>
        ifInstanceOf[MinClipOrbitView](view) { v => v.ensureMaxFlightAltitude(Length.feet2Meters(d))}
      }
    }

    if (config.getBooleanOrElse("offline", false)) WorldWind.setOfflineMode(true)

    wwd.setModel(WorldWind.createConfigurationComponent(AVKey.MODEL_CLASS_NAME).asInstanceOf[Model])
    //wwd.getSceneController().setDeepPickEnabled(true)
    setWwdMenu(config,raceView,wwd)

    wwd
  }

  def loadMapCursor: Cursor = {
    val defaultMapCursor = "mapcursor-black-white-32x32.png"
    val mapCursor = config.getStringOrElse("mapcursor", defaultMapCursor)
    val url: URL = ifNull(getClass.getResource(mapCursor))(getClass.getResource(defaultMapCursor))

    val icon = Swing.Icon(url)
    Toolkit.getDefaultToolkit.createCustomCursor(icon.getImage,new Point(16,16),"map cursor")
  }

  def setWwdMenu(config: Config, raceView: RaceView, wwd: WorldWindowGLCanvas) = {
    val showConsole = config.getBooleanOrElse("show-console", true)

    // we can't use scala-swing for menus since wwd is either a native window or a JPanel

    //--- the popup menu
    val popup = new JPopupMenu()
    popupShowPanelsMI = new JCheckBoxMenuItem("show console panel", showConsole)
    popup.add(popupShowPanelsMI)

    wwd.addMouseListener(new MouseAdapter(){
      override def mousePressed(e: MouseEvent): Unit = {
        if (e.isPopupTrigger){
          popup.show(e.getComponent, e.getX, e.getY)
        }
      }
    })

    //--- the menubar
    val menuBar = new JMenuBar
    val showMenu = new JMenu("Show")
    mbShowPanelsMI = new JCheckBoxMenuItem("show console panel", showConsole)
    mbShowPanelsMI.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, ActionEvent.ALT_MASK))
    showMenu.add(mbShowPanelsMI)
    menuBar.add(showMenu)
    peer.setJMenuBar(menuBar)

    val showConsoleListener = new ItemListener {
      override def itemStateChanged(e: ItemEvent): Unit = {
        val isSelected = e.getStateChange == ItemEvent.SELECTED
        raceView.showConsolePanels(isSelected)
        popupShowPanelsMI.setSelected(isSelected)
        mbShowPanelsMI.setSelected(isSelected)
      }
    }

    popupShowPanelsMI.addItemListener( showConsoleListener)
    mbShowPanelsMI.addItemListener( showConsoleListener)
  }

  def initializePanel (e: PanelEntry) = consolePanel.add( e.name, e.component, e.tooltip, e.expand)

  /**
    * dynamic view modifications during runtime
    */
  def setPanel (name: String, panel: Component) = consolePanel.set(name, panel)

  /**
    * set visibility of all active console panels
    *
    * TODO - GlCanvas does not resize properly on OS X
    */
  def showConsolePanels(setVisible: Boolean) = {
    if (consoleWrapper.visible != setVisible) {
      consoleWrapper.visible = setVisible
      top.revalidate

      worldPanel.reInit // required for OS X to adapt the GLCanvas size & position

      popupShowPanelsMI.setSelected(setVisible)
      mbShowPanelsMI.setSelected(setVisible)
    }
  }

  def showConsolePanel (name: String, setVisible: Boolean) = consolePanel.expand(name, setVisible)

  // this is executed in response to clicking the close button on the window frame, prior to disposing the frame itself
  override def closeOperation() = {
    visible = false

    //raceView.requestRaceTermination // FIXME - causes termination hang
  }

}
