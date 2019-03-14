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
package gov.nasa.race.ww.track

import java.awt.Font
import java.awt.image.BufferedImage

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{ThresholdLevel, ThresholdLevelList}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.track.TrajectoryPairEvent
import gov.nasa.race.trajectory.Trajectory
import gov.nasa.race.util.DateTimeUtils.hhmmss
import gov.nasa.race.ww
import gov.nasa.race.ww.{AltitudeSensitiveRaceLayer, ConfigurableRenderingLayer, DynamicLayerInfoPanel, Images, LayerObject, LayerSymbol, LayerSymbolOwner, RaceLayer, RaceViewer, SubscribingRaceLayer, WWPosition}
import gov.nasa.worldwind.render._

import scala.collection.mutable.{Map => MutableMap}

/**
  * the connector between the TrajectoryPairEvent and associated renderables
  */
class TrajectoryPairEventEntry (val event: TrajectoryPairEvent, val layer: TrajectoryPairEventLayer)
                extends LayerObject with LayerSymbolOwner {

  protected var isVisible = true
  protected var symbolShowing = false
  protected var pathsShowing = false  // only set if isVisible, depending on view level

  val dotAttrs = yieldInitialized(new PointPlacemarkAttributes) { attrs=>
    attrs.setLabelMaterial(labelMaterial)
    attrs.setLineMaterial(labelMaterial)
    attrs.setScale(7d)
    attrs.setImage(null)
    attrs.setUsePointAsDefaultImage(true)
  }

  //--- the renderables that are associated with this entry
  protected var symbol: LayerSymbol = createSymbol
  protected var dot1:   PointPlacemark = createDot(event.pos1)
  protected var path1:  Path = createPath(event.trajectory1)
  protected var dot2:   PointPlacemark = createDot(event.pos2)
  protected var path2:  Path = createPath(event.trajectory2)

  addSymbol

  //--- override in subclasses for specific rendering
  def createSymbol: LayerSymbol = new LayerSymbol(this)

  def createDot (pos: GeoPosition): PointPlacemark = yieldInitialized(new PointPlacemark(ww.wwPosition(pos))){ dot=>
    dot.setLabelText(null)
    dot.setAttributes(dotAttrs)
  }

  def createPath (traj: Trajectory): TrajectoryPath = new TrajectoryPath(traj,lineMaterial)

  def addSymbol = {
    if (!symbolShowing) {
      layer.addRenderable(symbol)
      symbolShowing = true
    }
  }

  def removeSymbol = {
    if (symbolShowing) {
      layer.removeRenderable(symbol)
      symbolShowing = false
    }
  }

  def addPaths = {
    if (!pathsShowing) {
      layer.addRenderable(dot1)
      layer.addRenderable(path1)
      layer.addRenderable(dot2)
      layer.addRenderable(path2)
      pathsShowing = true
    }
  }

  def removePaths = {
    if (pathsShowing) {
      layer.removeRenderable(dot1)
      layer.removeRenderable(path1)
      layer.removeRenderable(dot2)
      layer.removeRenderable(path2)
      pathsShowing = false
    }
  }


  //--- called by rendering levels (e.g. when changing eye altitude)

  def setDotLevel: Unit = { // only event dot
    symbol.setDotAttrs
    removePaths
  }
  def setLabelLevel: Unit = { // only event label
    symbol.setLabelAttrs
    removePaths
  }
  def setIconLevel: Unit = { // icon, label and paths
    symbol.setIconAttrs
    if (isVisible) {
      addPaths
    }
  }

  override def id: String = event.id
  override def pos: GeoPositioned = event

  override def isFocused = false // no focus support (yet) - this is static
  override def setFocused(cond: Boolean): Unit = {}

  override def labelMaterial: Material = layer.labelMaterial
  override def lineMaterial: Material = layer.lineMaterial
  override def symbolImg: BufferedImage = layer.symbolImg
  override def symbolImgScale: Double = 1.0
  override def symbolHeading: Double = 0.0
  override def labelFont: Font = layer.labelFont
  override def subLabelFont: Font = layer.subLabelFont

  override def labelOffset: Offset = TrackSymbol.LabelOffset
  override def iconOffset: Offset = TrackSymbol.IconOffset
  override def wwPosition: WWPosition = ww.wwPosition(event.position)
  override def displayName: String = s"${event.id}\n${hhmmss.print(event.date)}\n${event.eventType}"

  //--- label and info text creation
  override def labelText: String = event.id

  def show (showIt: Boolean): Unit = {
    if (showIt) {
      if (!isVisible) {
        addSymbol
      }
    } else {
      removePaths
      removeSymbol
    }
    isVisible = showIt
  }
}

/**
  * a RACE layer to control display of TrajectoryPairEvents
  */
class TrajectoryPairEventLayer (val raceViewer: RaceViewer, val config: Config)
              extends SubscribingRaceLayer with ConfigurableRenderingLayer with AltitudeSensitiveRaceLayer {

  val panel = new DynamicLayerInfoPanel
  val events = MutableMap[String,TrajectoryPairEventEntry]()

  val iconLevel = new ThresholdLevel[TrajectoryPairEventEntry](iconThresholdLevel)(setIconLevel)
  val labelLevel = new ThresholdLevel[TrajectoryPairEventEntry](labelThresholdLevel)(setLabelLevel)
  val symbolLevels = new ThresholdLevelList(setDotLevel).sortIn(labelLevel,iconLevel)

  def defaultSymbolImg: BufferedImage = Images.getEventImage(color)
  val symbolImg = defaultSymbolImg

  def setDotLevel(e: TrajectoryPairEventEntry): Unit = e.setDotLevel
  def setLabelLevel(e: TrajectoryPairEventEntry): Unit = e.setLabelLevel
  def setIconLevel(e: TrajectoryPairEventEntry): Unit = e.setIconLevel

  override def size: Int = events.size
  override def checkNewEyeAltitude: Unit = symbolLevels.triggerForEachValue(eyeAltitude,events)

  override def handleMessage: PartialFunction[Any, Unit] = {
    case BusEvent(_, e: TrajectoryPairEvent, _) => updateEvents(e)
  }

  def updateEvents(event: TrajectoryPairEvent): Unit = {
    incUpdateCount

    events.get(event.id) match {
      case Some(entry) =>
        // TODO - not sure if entries should be mutable
      case None =>
        val entry = new TrajectoryPairEventEntry(event, this)
        events += event.id -> entry
        entry.show(true)
        symbolLevels.triggerInCurrentLevel(entry) // this sets rendering attributes
    }

    wwdRedrawManager.redraw
    // the layerInfo panel does update periodically on its own
    //if (entryPanel.isShowing(entry)) entryPanel.update
  }
}
