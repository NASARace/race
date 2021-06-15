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

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{ItemRenderPanel, ListItemRenderer, VisibilityBounding}
import gov.nasa.race.ww.LayerInfoList._
import gov.nasa.worldwind.layers.Layer

import scala.language.postfixOps
import scala.swing._
import scala.swing.event.{ButtonClicked, ListSelectionChanged, MouseClicked}

/**
  * panel to display and manipulate all WorldWind layers
  *
  * note this also includes LayerInfo entries for layers that are not RaceLayers
  */
class LayerListPanel (raceView: RaceViewer, config: Option[Config]=None)
                                                     extends BoxPanel(Orientation.Vertical)
                                                       with RacePanel with LayerController {

  val layerInfoList = new LayerInfoList(raceView)
  val allCategories = layerInfoList.getCategories
  var selectedCategories = raceView.configuredLayerCategories(allCategories)
  val categoryCheckBoxes = allCategories.toSeq.sorted.map(new CheckBox(_).styled())
  categoryCheckBoxes foreach { cb =>
    cb.selected = selectedCategories.contains(cb.text)
    listenTo(cb)
  }
  reactions += {
    case ButtonClicked(cb) =>
      if (cb.selected) selectedCategories = selectedCategories + cb.text
      else selectedCategories = selectedCategories - cb.text

      listView.listData = getListData(selectedCategories, layerInfoList)
  }

  val categoryPanel = new GridPanel ((categoryCheckBoxes.size+3)/4, 4) {
    contents ++= categoryCheckBoxes
  }.styled()
  contents += categoryPanel

  // note this is only used for rendering, we can't listen to components
  class LayerInfoRendererPanel extends ItemRenderPanel[Layer] {
    val checkBox: CheckBox = new CheckBox().styled("layerEnabled")
    val nameLabel: Label = new Label().styled("layerName")
    val catLabel: Label = new Label().styled("layerCategory")

    val c = new Constraints(fill=Fill.Horizontal, anchor=Anchor.West)
    layout(checkBox)  = c(0,0)
    layout(nameLabel) = c(1,0)
    layout(catLabel)  = c(2,0).weightx(0.5)

    def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, layer: Layer, index: Int) = {
      val layerInfo = layer.layerInfo
      checkBox.selected = layer.isEnabled
      nameLabel.text = layerInfo.name
      catLabel.text = layerInfo.categories.mkString(",")
    }
  }
  val layerInfoRenderer = new LayerInfoRendererPanel

  val listView = new ListView[Layer](getListData(selectedCategories,layerInfoList))
                      with VisibilityBounding[Layer].styled("layerList")
  listView.maxVisibleRows = 8 // FIXME should be configured
  listView.renderer = new ListItemRenderer(layerInfoRenderer)
  val scrollPane = new ScrollPane(listView).styled("verticalIfNeeded")
  contents += scrollPane

  listenTo(listView.mouse.clicks)
  reactions += {
    case MouseClicked(`listView`,pos,mod,clicks,triggerPopup) =>
      if (layerInfoRenderer.checkBox.bounds.contains(pos.x,0)) {
        listView.selection.items.foreach { layer =>
          layer.toggleEnabled
          raceView.layerChanged(layer)
        }
        listView.repaint()
      }
      updateLayerInfoPanel(getFirstSelected)
  }

  val toolTipController = new ToolTipController(raceView.wwd, layerInfoList.tooltipAnnotationLayer)
  val highlightController = new HighlightController(raceView.wwd)

  var lastLayerInfoPanel: Option[LayerInfoPanel] = None

  raceView.setLayerController(this)

  //--- methods
  def getListData(sel: Set[String], candidates: LayerInfoList) = candidates.filter(sel)

  def getFirstSelected: Option[Layer] = listView.selection.items.headOption

  def updateLayerInfoPanel (layerSelection: Option[Layer]) = {
    ifSome(layerSelection) { layer =>
      val panel = layer.layerInfo.panel
      if (lastLayerInfoPanel.isEmpty || lastLayerInfoPanel.get.layer != layer) {
        ifSome(lastLayerInfoPanel){ _.unselect}
        ifInstanceOf[SharedLayerInfoPanel](panel) {_.setLayer(layer)}
        lastLayerInfoPanel = Some(panel)

        raceView.setLayerPanel(panel)
        panel.select

        raceView.layerChanged(layer)
      }
    }
  }

  def changeLayer (name: String, enable: Boolean) = {
    layerInfoList.find(name).foreach { layer =>
      layer.setEnabled(enable)
      listView.repaint()
    }
  }
}


