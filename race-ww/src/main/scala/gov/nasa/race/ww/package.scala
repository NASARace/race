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

package gov.nasa.race

import java.awt.Color
import java.awt.event._

import com.typesafe.config.Config
import gov.nasa.race.geo.{GeoPosition, _}
import gov.nasa.worldwind._
import gov.nasa.worldwind.event.SelectEvent
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.layers.Layer
import javax.swing._

import scala.language.{implicitConversions, reflectiveCalls}
import scala.swing.{BorderPanel, Component, Panel}


package object ww {

  type AppPanelCreator = (WorldWindow) => Panel
  type WWAngle = gov.nasa.worldwind.geom.Angle
  type WWPosition = gov.nasa.worldwind.geom.Position

  implicit class FrameWrapper[T <: JFrame] (frame: T) {
    def launch: T = {
      SwingUtilities.invokeLater( new Runnable(){
        def run(): Unit = frame.setVisible(true)
      })
      frame
    }

    def uponClose (action: => Unit) = {
      frame.addWindowListener( new WindowAdapter() {
        override def windowClosed (event: WindowEvent): Unit = {
          action
        }
      })
    }
  }

  val FarAway = Position.fromDegrees(0,0,Double.MaxValue) // use this for invisible positions

  @inline def wwPosition(pos: GeoPosition): Position = {
    Position.fromDegrees(pos.latDeg, pos.lonDeg, pos.altMeters)
  }

  val ZeroWWAngle = gov.nasa.worldwind.geom.Angle.ZERO

  def redrawAsync (wwd: WorldWindow) = {
    SwingUtilities.invokeLater( new Runnable(){
      def run(): Unit = wwd.redraw()
    })
  }

  def toARGBString (clr: Color): String =
    f"0x${clr.getAlpha}%02x${clr.getRed}%02x${clr.getGreen}%02x${clr.getBlue}%02x"
  def toABGRString (clr: Color): String =
    f"0x${clr.getAlpha}%02x${clr.getBlue}%02x${clr.getGreen}%02x${clr.getRed}%02x"

  trait ViewListener {
    def viewChanged (targetView: ViewGoal): Unit
  }

  trait LayerListener {
    def layerChanged(layer: Layer): Unit
  }
  trait LayerController {
    def changeLayer (name: String, isEnabled: Boolean): Unit
  }


  case class PanelEntry ( name: String,
                          panel: RacePanel,
                          tooltip: String="click to hide/show panel",
                          var expand: Boolean=true)

  object EventAction extends Enumeration {
    type EventAction = Value
    val LeftClick, RightClick, LeftDoubleClick, Hover, Rollover, Unknown = Value
  }
  def getEventAction(e: SelectEvent) = {
    val a = e.getEventAction
    if (a.eq(SelectEvent.LEFT_CLICK)) EventAction.LeftClick
    else if (a.eq(SelectEvent.RIGHT_CLICK)) EventAction.RightClick
    else if (a.eq(SelectEvent.LEFT_DOUBLE_CLICK)) EventAction.LeftDoubleClick
    else if (a.eq(SelectEvent.HOVER)) EventAction.Hover
    else if (a.eq(SelectEvent.ROLLOVER)) EventAction.Rollover
    else EventAction.Unknown
  }

  // this is a WW specific problem - make colored label text more visible against a multi-colored globe background
  // labels will be drawn with a black shadow so we want to increase the contrast for "dark" colors such as blue or "red"
  // (Color.brighter will only multiply, i.e. does not change the color if components are max or 0)
  def lighter (color: Color): Color = {
    val shift = 64
    val r = Math.min(255,color.getRed + shift)
    val g = Math.min(255,color.getGreen + shift)
    val b = Math.min(255,color.getBlue + shift)
    new Color(r,g,b)
  }

  /**
    * abstract RACE panel type that provides default notification callbacks
    */
  trait RacePanel extends Component {
    def onRaceInitialized: Unit = {}
    def onRaceStarted: Unit = {}
    def onRacePaused: Unit = {}
    def onRaceResumed: Unit = {}
    def onRaceTerminated: Unit = {}
  }

  /**
    * just a placeholder for no content
    */
  class EmptyPanel (raceView: RaceViewer, config: Option[Config]=None) extends BorderPanel with RacePanel

  // animation and selection hints. Note these are used for identity comparison (use refs and eq)

  final val NoAnimation = "NoAnimation"
  final val Center = "Center"
  final val Zoom = "Zoom"
  final val Pan = "Pan"
  final val EyePos = "EyePos"
  final val FlyTo = "FlyTo"
  final val Heading = "Heading"
  final val Pitch = "Pitch"
  final val Roll = "Roll"
  final val Goto = "Goto"  // the catch all

  final val SelectedLayer = "selected layer"
  final val SelectedObject = "selected object"
}