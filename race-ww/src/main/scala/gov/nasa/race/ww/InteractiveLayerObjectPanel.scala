/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.awt.Insets

import gov.nasa.race._
import gov.nasa.race.swing.GBPanel.Anchor
import gov.nasa.race.swing.{FieldPanel, GBPanel, InstrumentedCollapsibleComponent}
import gov.nasa.race.swing.Style._

import scala.swing.event.{ButtonClicked, MouseClicked}
import scala.swing.{Alignment, BoxPanel, Button, CheckBox, Label, Orientation, ToolBar}

/**
  * base type for panels that display details about the selected LayerObject and allow
  * to manipulate its display attributes
  */
abstract class InteractiveLayerObjectPanel[T <: LayerObject, U <: FieldPanel](val layer: InteractiveRaceLayer[T])
                          extends BoxPanel(Orientation.Vertical)
                            with RacePanel with InstrumentedCollapsibleComponent {
  protected var entry: Option[T] = None  // panel can be reused for different objects

  val fields: U = createFieldPanel
  protected def createFieldPanel: U // to be provided by concrete type

  // the check boxes to control display attributes
  val pathCb = new CheckBox("path").styled()
  val contourCb = new CheckBox("3d").styled()
  val infoCb = new CheckBox("info").styled()
  val markCb = new CheckBox("mark").styled()
  val focusCb = new CheckBox("focus").styled()

  val dismissBtn = new Label("",Images.getScaledIcon("eject-blue-16x16.png"),Alignment.Center).styled()

  val buttonPanel = new GBPanel {
    val c = new Constraints(insets = new Insets(5, 0, 0, 0), anchor = Anchor.West)
    layout(pathCb)     = c(0,0)
    layout(contourCb)   = c(1,0)
    layout(infoCb)     = c(2,0)
    layout(markCb)     = c(3,0)
    layout(focusCb)    = c(4,0)
  }.styled()

  contents += fields
  contents += buttonPanel

  listenTo(focusCb,pathCb,contourCb,infoCb,markCb, dismissBtn.mouse.clicks)
  reactions += {
    case ButtonClicked(`focusCb`)  => ifSome(entry) { layer.focusLayerObject(_,focusCb.selected) }
    case ButtonClicked(`pathCb`)   => ifSome(entry) { layer.setLayerObjectAttribute(_,LayerObjectAttribute.Path,pathCb.selected) }
    case ButtonClicked(`contourCb`) => ifSome(entry) { layer.setLayerObjectAttribute(_,LayerObjectAttribute.Contour,contourCb.selected) }
    case ButtonClicked(`infoCb`)   => ifSome(entry) { layer.setLayerObjectAttribute(_,LayerObjectAttribute.Info,infoCb.selected) }
    case ButtonClicked(`markCb`)   => ifSome(entry) { layer.setLayerObjectAttribute(_,LayerObjectAttribute.Mark,markCb.selected) }
    case MouseClicked(`dismissBtn`,_,_,_,_) => dismissPanel
  }

  override def getToolBar: Option[ToolBar] = {
    val tb = new ToolBar().styled()
    tb.contents += dismissBtn
    tb.peer.setFloatable(false)
    Some(tb)
  }

  def raceView: RaceViewer = layer.raceViewer

  def updateEntryAttributes = ifSome(entry) { e=>
    focusCb.selected = e.isFocused
    pathCb.selected = e.isAttrSet(LayerObjectAttribute.Path)
    contourCb.selected = e.isAttrSet(LayerObjectAttribute.Contour)
    infoCb.selected = e.isAttrSet(LayerObjectAttribute.Info)
    markCb.selected = e.isAttrSet(LayerObjectAttribute.Mark)
  }

  def isShowing (e: T): Boolean = peer.isShowing && entry.isDefined && entry.get == e

  def dismissPanel: Unit = ifSome(entry) { e =>
    layer.dismissLayerObjectPanel(e)
    reset
  }

  def reset: Unit = entry = None
}
