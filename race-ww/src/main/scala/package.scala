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
import javax.swing._

import gov.nasa.race.data.{LatLonPos, Track, _}
import gov.nasa.worldwind._
import gov.nasa.worldwind.event.SelectEvent
import gov.nasa.worldwind.geom.{LatLon, Position, Angle => WWAngle}
import gov.nasa.worldwind.layers.Layer
import squants.space.{Angle, Length}

import scala.language.{implicitConversions, reflectiveCalls}
import scala.swing.{Component, Panel}


package object ww {

  type AppPanelCreator = (WorldWindow) => Panel

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

  def toWWPosition (pos: LatLonPos, alt: Length): Position = {
    Position.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees, alt.toMeters)
  }

  implicit def toWWPosition (p: AltitudePositionable): Position = toWWPosition(p.position, p.altitude)

  implicit def toWWPosition (pos: LatLonPos): Position = Position.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def toWWPosition (t: Track): Position = toWWPosition(t.pos, t.altitude.getOrElse(Length0))

  implicit def angleToWWAngle (angle: Angle): WWAngle = WWAngle.fromDegrees(angle.toDegrees)

  implicit def latLonPos2LatLon (pos: LatLonPos): LatLon = LatLon.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def LatLon2LatLonPos (latLon: LatLon): LatLonPos = LatLonPos.fromDegrees( latLon.latitude.degrees, latLon.longitude.degrees)

  val ZeroWWAngle = WWAngle.fromDegrees(0)

  def redrawAsync (wwd: WorldWindow) = {
    SwingUtilities.invokeLater( new Runnable(){
      def run(): Unit = wwd.redraw()
    })
  }

  def toARGBString (clr: Color): String =
    f"0x${clr.getAlpha}%02x${clr.getRed}%02x${clr.getGreen}%02x${clr.getBlue}%02x"
  def toABGRString (clr: Color): String =
    f"0x${clr.getAlpha}%02x${clr.getBlue}%02x${clr.getGreen}%02x${clr.getRed}%02x"

  trait EyePosListener {
    def eyePosChanged(pos: Position, animationHint: String)
  }
  trait LayerListener {
    def layerChanged(layer: Layer)
  }
  trait LayerController {
    def changeLayer (name: String, isEnabled: Boolean)
  }

  trait LayerObject {
    def id: String
    def layer: Layer
  }

  trait ObjectListener {
    def objectChanged(obj: LayerObject, action: String)
  }
  // generic actions (would be nice if we could extend enumerations)
  // this is an open set - layers can introduce their own actions
  final val Select = "Select"
  final val ShowPanel = "ShowPanel"
  final val DismissPanel = "DismissPanel"

  case class PanelEntry (name: String, component: Component, tooltip: String="click to expand/shrink", expand: Boolean=true)

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
}