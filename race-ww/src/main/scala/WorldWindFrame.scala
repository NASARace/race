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
import java.awt.{Cursor, Toolkit}
import javax.swing._

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
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

  val wwd = createWorldWindow(config, raceView)
  val worldPanel = new AWTWrapper(wwd).styled('world)
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

    ifSome(config.getOptionalConfig("eye")) { e =>
      val eyePos = Position.fromDegrees(e.getDouble("lat"), e.getDouble("lon"), e.getDouble("altitude-ft"))
      wwd.getView.setEyePosition(eyePos)
    }

    if (config.getBooleanOrElse("offline", false)) WorldWind.setOfflineMode(true)

    wwd.setModel(WorldWind.createConfigurationComponent(AVKey.MODEL_CLASS_NAME).asInstanceOf[Model])
    //wwd.getSceneController().setDeepPickEnabled(true)
    setWwdMenu(config,raceView,wwd)

    wwd
  }

  def loadMapCursor: Cursor = {
    val icon = Swing.Icon(getClass.getResource("mapcursor-33.png"))
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
    */
  def showConsolePanels(setVisible: Boolean) = {
    consoleWrapper.visible = setVisible
    top.revalidate

    popupShowPanelsMI.setSelected(setVisible)
    mbShowPanelsMI.setSelected(setVisible)
  }

  def showConsolePanel (name: String, setVisible: Boolean) = consolePanel.expand(name, setVisible)

  override def closeOperation() = { // this is executed in response to close() within the EDT
    visible = false

    WorldWind.shutDown()
    Thread.sleep(500) // <2do> we should wait for the WW "AWT-AppKit" to terminate
    flushEventQueue() // make sure we don't have any pending timer or WW events
  }
}
