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

import gov.nasa.race._
import gov.nasa.race.swing.Style._
import gov.nasa.worldwind.event.SelectListener
import gov.nasa.worldwind.layers._
import gov.nasa.worldwind.layers.placename.PlaceNameLayer

import scala.jdk.CollectionConverters._
import scala.collection.mutable.Buffer

object LayerInfoList {
  val defaultPanel = new SharedLayerInfoPanel().styled("consolePanel")

  class WWLayerInfo (val name: String, val categories: Set[String], val description: String,
                     val enable: Boolean, val enablePick: Boolean,
                     val panel: LayerInfoPanel,
                     var layer: Layer=null) extends RaceLayerInfo {
    override def getLayer = Some(layer)
  }

  class Graticule (val name: String = "Graticule", val categories: Set[String] = Set("deco"),
                   val description: String = "latitude/longitude grid",
                   val enable: Boolean = false, val enablePick: Boolean = false,
                   val panel: LayerInfoPanel = defaultPanel) extends LatLonGraticuleLayer with RaceLayerInfo

  class WWViewControls (val name: String = "View Controls", val categories: Set[String] = Set("widget"),
                        val description: String = "view navigation buttons",
                        val enable: Boolean = true, val enablePick: Boolean = true,
                        val panel: LayerInfoPanel = defaultPanel) extends ViewControlsLayer with RaceLayerInfo

  class TTAnnotations (val name: String = "Tooltip Annotations", val categories: Set[String] = Set("deco"),
                        val description: String = "tooltip annotations",
                        val enable: Boolean = true, val enablePick: Boolean = false,
                        val panel: LayerInfoPanel = defaultPanel) extends AnnotationLayer with RaceLayerInfo

  val unknownWWLayer = new WWLayerInfo("", Set[String](), "", true, false, defaultPanel)

  val systemLayerMap: Map[String,WWLayerInfo] = Map(
    Seq( new WWLayerInfo("Stars", Set("deco"), "star background for globe", true, false, defaultPanel),
      new WWLayerInfo("Atmosphere", Set("deco"), "atmosphere for globe", true, false, defaultPanel),

      new WWLayerInfo("WorldMap", Set("map"), "high level world map", true, false, defaultPanel),
      new WWLayerInfo("StamenTerrain", Set("map"), "Stamen.com terrain map", true, false, defaultPanel),
      new WWLayerInfo("OSMHumanitarian", Set("map"), "OpenStreetMap Humanitarian style", true, false, defaultPanel),

      new WWLayerInfo("World Map", Set("widget"), "navigation control", false, false, defaultPanel),
      new WWLayerInfo("Scale bar", Set("widget"), "", true, false, defaultPanel),
      new WWLayerInfo("View Controls", Set("widget"), "navigation control buttons", true, false, defaultPanel),
      new WWLayerInfo("Compass", Set("widget"), "", true, false, defaultPanel)
    ).map( e => (e.name, e)): _*)

  implicit class RichLayer (layer: Layer) {
    def layerInfo: RaceLayerInfo = {
      layer match {
        case l: RaceLayerInfo => l
        case l => systemLayerMap.getOrElse(l.getName, unknownWWLayer)
      }
    }

    def toggleEnabled = {
      layer.setEnabled( !layer.isEnabled)
    }
  }

  final val ExclusiveMarker = "!"
}
import gov.nasa.race.ww.LayerInfoList._

/**
 * this shadows the worldwind layerlist and is the basis for display/control
 * of layers
 * Note that we therefore need to preserve the order
 */
class LayerInfoList (raceView: RaceViewer) {
  val wwd = raceView.wwd
  val applicationLayers = raceView.layers

  val layerList: Buffer[Layer] = wwd.getModel.getLayers.asScala // these are WorldWind's configured system layers

  val tooltipAnnotationLayer = new TTAnnotations()
  def redrawManager = raceView.redrawManager

  addSystemLayers
  addApplicationLayers
  setInitialStatus

  def addSystemLayers: Unit = {
    val viewControlsLayer = new WWViewControls
    insertBeforeCompass(viewControlsLayer)
    wwd.addSelectListener(new ViewControlsSelectListener(wwd, viewControlsLayer))

    insertBeforeCompass(tooltipAnnotationLayer)

    val graticule = new Graticule()
    insertBeforeCategory(graticule, "widget")
  }

  def addApplicationLayers = {
    val lowestWidgetIdx = getLowestCategoryIndex("widget")
    // reverse so that later layers are on top
    for (layer <- applicationLayers.reverse) {
      layerList.insert(lowestWidgetIdx, layer)

      if (layer.isInstanceOf[RaceLayerInfo]) layer.asInstanceOf[RaceLayerInfo].initializeLayer(wwd, redrawManager)
      if (layer.isInstanceOf[SelectListener]) wwd.addSelectListener(layer.asInstanceOf[SelectListener])
    }
  }

  def setInitialStatus = {
    for (layer <- layerList) {
      val layerInfo = layer.layerInfo
      if (layerInfo eq unknownWWLayer) {
        raceView.log.warning(s"unknown layer: ${layer.getName}")
      } else {
        if (layerInfo.enable != layer.isEnabled) layer.setEnabled(layerInfo.enable)
        if (layerInfo.enablePick != layer.isPickEnabled) layer.setPickEnabled(layerInfo.enablePick)
      }
    }
  }

  def getCategories: Set[String] = layerList.foldLeft(Set[String]()) { (set,e) => set ++ e.layerInfo.categories }

  def filter (categories: Set[String]) = layerList.filter( layer => containsAny(categories, layer.layerInfo.categories))

  def find (name: String): Option[Layer] = layerList.find( (l) => l.getName == name)

  //--- adding/removing layers
  def insertIfAbsent (newLayer: Layer, before: Boolean=true)(pred: (Layer) => Boolean): Int = {
    var idx=0
    for (layer <- layerList) {
      if (pred(layer)) {
        if (!before) idx += 1
        layerList.insert(idx, newLayer)
        return idx
      } else if (layer eq newLayer)
        return -1
      else
        idx += 1
    }
    -1
  }

  def insertBeforeCompass(newLayer: Layer) = insertIfAbsent(newLayer){_.isInstanceOf[CompassLayer]}
  def insertBeforePlacenames(newLayer: Layer) = insertIfAbsent(newLayer){_.isInstanceOf[PlaceNameLayer]}
  def insertAfterPlacenames(newLayer: Layer) = insertIfAbsent(newLayer,false){_.isInstanceOf[PlaceNameLayer]}
  def insertBeforeLayerName(newLayer: Layer,name: String) = insertIfAbsent(newLayer){ _.getName.indexOf(name) != -1 }

  def insertBeforeCategory (newLayer: Layer, cat: String) = {
    val idx = getLowestCategoryIndex(cat)
    if (idx < 0){
      layerList += newLayer
    } else {
      layerList.insert(idx, newLayer)
    }
  }

  def getLowestCategoryIndex (cat: String): Int = {
    for (i <- 1 until layerList.size) {
      if (layerList(i).layerInfo.categories.contains(cat)) return i
    }
    layerList.size
  }
}
