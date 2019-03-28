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
import gov.nasa.race.common.{Query, ThresholdLevel, ThresholdLevelList}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.swing.FieldPanel
import gov.nasa.race.swing.Style._
import gov.nasa.race.track.TrajectoryPairEvent
import gov.nasa.race.trajectory.Trajectory
import gov.nasa.race.util.DateTimeUtils.hhmmss
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.{ww, _}
import gov.nasa.race.ww.LayerObjectAttribute.LayerObjectAttribute
import gov.nasa.race.ww.{AltitudeSensitiveRaceLayer, ConfigurableRenderingLayer, EventAction, Images, InteractiveLayerInfoPanel, InteractiveLayerObjectPanel, InteractiveRaceLayer, LayerObject, LayerObjectAction, LayerObjectAttribute, LayerSymbol, LayerSymbolOwner, RaceLayerPickable, RaceViewer, SubscribingRaceLayer, WWPosition}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render._

import scala.collection.mutable.{Map => MutableMap}


class TrajectoryPairEventQuery[T] (getEvent: T=>TrajectoryPairEvent) extends Query[T] {
  override def error(msg: String): Unit = {
    // TODO
  }

  override def getMatchingItems(query: String, items: Iterable[T]): Iterable[T] = {
    items // TODO
  }
}

class EventFields extends FieldPanel {
  val id = addField("id:")
  val etype = addField("type:")
  val details = addField("details:")
  val time = addField("time:")

  addSeparator
  val track1 = addField("track-1:")
  val pos1 = addField("pos-1")
  val alt1 = addField("alt-1:")
  val hdg1 = addField("hdg-1:")
  val spd1 = addField("spd-1:")

  addSeparator
  val track2 = addField("track-2:")
  val pos2 = addField("pos-2")
  val alt2 = addField("alt-2:")
  val hdg2 = addField("hdg-2:")
  val spd2 = addField("spd-2:")

  setContents

  def update (e: TrajectoryPairEvent): Unit = {
    id.text = e.id
    etype.text = e.eventType
    details.text = e.eventDetails
    time.text = hhmmss.print(e.date)

    track1.text = e.track1.cs
    pos1.text = f"${e.pos1.latDeg}%.6f°   ${e.pos1.lonDeg}%.6f°"
    alt1.text = s"${e.pos1.altFeet} ft"
    hdg1.text = f"${e.hdg1.toDegrees}%3.0f°"
    spd1.text = s"${e.spd1.toKnots.toInt} kn"

    track2.text = e.track2.cs
    pos2.text = f"${e.pos2.latDeg}%.6f°   ${e.pos2.lonDeg}%.6f°"
    alt2.text = s"${e.pos2.altFeet} ft"
    hdg2.text = f"${e.hdg2.toDegrees}%3.0f°"
    spd2.text = s"${e.spd2.toKnots.toInt} kn"

  }
}

class TrajectoryPairEventPanel (override val layer: TrajectoryPairEventLayer)
                                       extends InteractiveLayerObjectPanel[TrajectoryPairEventEntry,EventFields](layer) {
  override def createFieldPanel = new EventFields

  def setEntry (e: TrajectoryPairEventEntry): Unit = {
    entry = Some(e)
    fields.update(e.event)
  }

}

/**
  * the connector between the TrajectoryPairEvent and associated renderables
  */
class TrajectoryPairEventEntry (val event: TrajectoryPairEvent, val layer: TrajectoryPairEventLayer)
                extends LayerObject with LayerSymbolOwner {

  protected var _isVisible = true
  protected var symbolShowing = false
  protected var marksShowing = false
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
  protected var posMarker1: PointPlacemark = createPosMarker(event.pos1)
  protected var path1:  Path = createPath(event.trajectory1)
  protected var posMarker2: PointPlacemark = createPosMarker(event.pos2)
  protected var path2:  Path = createPath(event.trajectory2)

  addSymbol

  //--- override in subclasses for specific rendering
  def createSymbol: LayerSymbol = new LayerSymbol(this)

  def createPosMarker (pos: GeoPosition): PointPlacemark = yieldInitialized(new PointPlacemark(ww.wwPosition(pos))){ p=>
    p.setLabelText(null)
    p.setAttributes(dotAttrs)
    p.setAltitudeMode(WorldWind.ABSOLUTE)
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

  def addMarks: Unit = {
    if (!marksShowing) {
      layer.addRenderable(posMarker1)
      layer.addRenderable(posMarker2)
      marksShowing = true
    }
  }

  def removeMarks: Unit = {
    if (marksShowing) {
      layer.removeRenderable(posMarker1)
      layer.removeRenderable(posMarker2)
      marksShowing = false
    }
  }

  def addPaths = {
    if (!pathsShowing) {
      layer.addRenderable(posMarker1)
      layer.addRenderable(path1)
      layer.addRenderable(posMarker2)
      layer.addRenderable(path2)
      pathsShowing = true
    }
  }

  def removePaths = {
    if (pathsShowing) {
      layer.removeRenderable(posMarker1)
      layer.removeRenderable(path1)
      layer.removeRenderable(posMarker2)
      layer.removeRenderable(path2)
      pathsShowing = false
    }
  }

  //--- called by rendering levels (e.g. when changing eye altitude)

  def setDotLevel: Unit = { // only event dot
    symbol.setDotAttrs
    removePaths
    removeMarks
  }
  def setLabelLevel: Unit = { // only event label
    symbol.setLabelAttrs
    removePaths
    removeMarks
  }
  def setIconLevel: Unit = { // icon, label and paths
    symbol.setIconAttrs
    if (_isVisible) {
      addSymbol
      addMarks
    }
  }

  override def id: String = event.id
  override def pos: GeoPositioned = event

  override def isVisible: Boolean = _isVisible
  override def setVisible(cond: Boolean): Unit = show(cond)

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
      if (!_isVisible) {
        addSymbol
      }
    } else {
      removePaths
      removeSymbol
    }
    _isVisible = showIt
  }

  override def setAttr(attr: LayerObjectAttribute, cond: Boolean): Unit = {
    attr match {
      case LayerObjectAttribute.Path => if (cond) addPaths else removePaths
      case LayerObjectAttribute.Info => // TBD
      case LayerObjectAttribute.Mark => // TDB
      case _ => // ignore
    }
  }

  override def isAttrSet(attr: LayerObjectAttribute): Boolean = {
    attr match {
      case LayerObjectAttribute.Path => pathsShowing
      case LayerObjectAttribute.Info => false
      case LayerObjectAttribute.Mark => false
      case _ => false
    }
  }
}

/**
  * a RACE layer to control display of TrajectoryPairEvents
  */
class TrajectoryPairEventLayer (val raceViewer: RaceViewer, val config: Config)
              extends SubscribingRaceLayer
                with ConfigurableRenderingLayer
                with AltitudeSensitiveRaceLayer
                with InteractiveRaceLayer[TrajectoryPairEventEntry] {

  val panel = createLayerInfoPanel
  val entryPanel = createEntryPanel

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

  //--- InteractiveRaceLayer interface

  override def layerObjects: Iterable[TrajectoryPairEventEntry] = events.values
  override def layerObjectQuery: Query[TrajectoryPairEventEntry] = {
    new TrajectoryPairEventQuery[TrajectoryPairEventEntry](_.event)
  }

  override def maxLayerObjectRows: Int = config.getIntOrElse("max-rows", 10)

  override def layerObjectIdHeader: String = "event"
  override def layerObjectIdText (e: TrajectoryPairEventEntry): String = e.id

  override def layerObjectDataHeader: String = "data"
  override def layerObjectDataText (e: TrajectoryPairEventEntry): String = {
    val ev = e.event
    s"${ev.eventType} : ${ev.eventDetails}"
  }

  override def setLayerObjectAttribute(e: TrajectoryPairEventEntry, attr: LayerObjectAttribute, cond: Boolean): Unit = {
    if (e.isAttrSet(attr) != cond) {
      e.setAttr(attr, cond)
      ifSome(LayerObjectAttribute.getAction(attr, cond)) { action =>
        raceViewer.objectChanged(e,action)
      }
    }
  }

  override def doubleClickLayerObject(e: TrajectoryPairEventEntry): Unit = {
    if (!entryPanel.isShowing(e)) {
      entryPanel.setEntry(e)
      raceViewer.setObjectPanel(entryPanel)
      raceViewer.objectChanged(e,LayerObjectAction.ShowPanel)
    }
  }

  override def focusLayerObject(o: TrajectoryPairEventEntry, cond: Boolean): Unit = {
    // TODO
  }

  override def dismissLayerObjectPanel (e: TrajectoryPairEventEntry): Unit = {
    if (entryPanel.isShowing(e)) {
      entryPanel.reset

      raceViewer.dismissObjectPanel
      raceViewer.objectChanged(e, LayerObjectAction.DismissPanel)
    }
  }

  //--- initialization support - override if we need specialized entries/init
  protected def createEventEntry (event: TrajectoryPairEvent): TrajectoryPairEventEntry = {
    new TrajectoryPairEventEntry(event, this)
  }

  protected def createLayerInfoPanel: InteractiveLayerInfoPanel[TrajectoryPairEventEntry] = {
    new InteractiveLayerInfoPanel(this).styled('consolePanel)
  }

  protected def createEntryPanel: TrajectoryPairEventPanel = new TrajectoryPairEventPanel(this)



  def updateEvents(event: TrajectoryPairEvent): Unit = {
    incUpdateCount

    events.get(event.id) match {
      case Some(entry) =>
        // TODO - not sure if entries should be mutable
      case None =>
        val entry = createEventEntry(event)
        events += event.id -> entry
        entry.show(true)
        symbolLevels.triggerInCurrentLevel(entry) // this sets rendering attributes
    }

    wwdRedrawManager.redraw
    // the layerInfo panel does update periodically on its own
    //if (entryPanel.isShowing(entry)) entryPanel.update
  }

  override def selectObject(o: RaceLayerPickable, a: EventAction) = {
    o.layerItem match {
      case e: TrajectoryPairEventEntry =>
        a match {
          case EventAction.LeftDoubleClick => doubleClickLayerObject(e)
          case _ => // ignored
        }
      case _ => // ignored
    }
  }
}
