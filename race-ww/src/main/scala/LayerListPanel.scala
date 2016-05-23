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

import java.awt.Rectangle

import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{GBPanel, Style, GBPanel$}
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.ww.LayerInfoList._
import gov.nasa.worldwind.layers.Layer

import scala.language.postfixOps
import scala.swing._
import scala.swing.event.{ButtonClicked, ListSelectionChanged, MouseClicked}

/**
 * default panel to display and manipulate WorldWind layers
 */
class LayerListPanel (raceView: RaceView, config: Option[Config]=None) extends BoxPanel(Orientation.Vertical)
                                                                       with LayerController {

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

  val categoryPanel = new GridPanel ((categoryCheckBoxes.size+2)/3, 3) {
    contents ++= categoryCheckBoxes
  } styled()
  contents += categoryPanel

  var cbBounds: Rectangle = _
  val listView: ListView[Layer] = new ListView(getListData(selectedCategories,layerInfoList)){
    renderer = new ListView.AbstractRenderer[Layer,LayerInfoRenderer](new LayerInfoRenderer().styled()) {
      def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, layer: Layer, index: Int) = {
        component.setLayer(layer)
        cbBounds = component.checkBoxBounds
      }
    }
  }.styled('layerList)
  val scrollPane = new ScrollPane(listView).styled()
  contents += scrollPane

  var selectionChanged = false
  listenTo(listView.mouse.clicks, listView.selection)
  reactions += {
    case MouseClicked(`listView`,pos,mod,clicks,triggerPopup) =>
      if (/* !selectionChanged && */ cbBounds != null && cbBounds.contains(pos.x,0)) {
        listView.selection.items.foreach { layer =>
          layer.toggleEnabled
          raceView.layerChanged(layer)
        }
        listView.repaint
      }
      selectionChanged = false

    case ListSelectionChanged(`listView`,range,live) =>
      selectionChanged = true
      updateLayerInfoPanel(getFirstSelected)
  }

  val toolTipController = new ToolTipController(raceView.wwd, layerInfoList.tooltipAnnotationLayer)
  val highlightController = new HighlightController(raceView.wwd)

  raceView.setLayerController(this)

  //--- methods
  def getListData(sel: Set[String], candidates: LayerInfoList) = candidates.filter(sel)

  def getFirstSelected: Option[Layer] = listView.selection.items.headOption

  def updateLayerInfoPanel (layerSelection: Option[Layer]) = {
    ifSome(layerSelection) { layer =>
      val panel = layer.layerInfo.panel
      panel.setLayer(layer)
      raceView.setLayerPanel(panel.asInstanceOf[Component])
    }
  }

  def changeLayer (name: String, enable: Boolean) = {
    layerInfoList.find(name).foreach { layer =>
      layer.setEnabled(enable)
      listView.repaint
    }
  }
}

// note this is only used for rendering, we can't listen to components
class LayerInfoRenderer extends GBPanel {
  var checkBox = new CheckBox().styled()
  var nameLabel = new Label().styled('layerName)
  var catLabel = new Label().styled('layerCategory)

  val c = new Constraints(fill=Fill.Horizontal, anchor=Anchor.West)
  layout(checkBox)  = c(0,0)
  layout(nameLabel) = c(1,0)
  layout(catLabel)  = c(2,0).weightx(0.5)

  def checkBoxBounds = checkBox.bounds

  def setLayer (layer: Layer) = {
    val layerInfo = layer.layerInfo
    checkBox.selected = layer.isEnabled
    nameLabel.text = layerInfo.name
    catLabel.text = layerInfo.categories.mkString(",")
  }

}

