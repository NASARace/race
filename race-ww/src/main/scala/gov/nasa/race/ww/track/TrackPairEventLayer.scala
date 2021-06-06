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

import java.awt.{Color, Font}
import java.awt.image.BufferedImage

import com.typesafe.config.Config
import gov.nasa.race.common.{Query, ThresholdLevel, ThresholdLevelList}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.swing.FieldPanel
import gov.nasa.race.swing.Style._
import gov.nasa.race.track.TrackPairEvent
import gov.nasa.race.trajectory.Trajectory
import gov.nasa.race.uom.Angle
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.LayerObjectAttribute.LayerObjectAttribute
import gov.nasa.race.ww.{AltitudeSensitiveRaceLayer, ConfigurableRenderingLayer, EventAction, Images, InteractiveLayerInfoPanel, InteractiveLayerObjectPanel, InteractiveRaceLayer, LayerObject, LayerObjectAction, LayerObjectAttribute, LayerSymbol, LayerSymbolOwner, RaceLayerPickable, RaceViewer, SubscribingRaceLayer, WWPosition}
import gov.nasa.race.{ww, _}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.render._

import scala.collection.mutable.{Map => MutableMap}


class TrackPairEventQuery[T](getEvent: T=>TrackPairEvent) extends Query[T] {
  override def error(msg: String): Unit = {
    // TODO
  }

  override def getMatchingItems(query: String, items: Iterable[T]): Iterable[T] = {
    items // TODO
  }
}

trait TrackPairEventFields extends FieldPanel {
  def update (e: TrackPairEvent): Unit
}

class GenericTrackPairEventFields extends TrackPairEventFields {
  val id = addField("id:")
  val etype = addField("type:")
  val details = addField("details:")
  val time = addField("time:")

  addSeparator
  val track1 = addField("track-1:")
  val pos1 = addField("pos-1:")
  val alt1 = addField("alt-1:")
  val hdg1 = addField("hdg-1:")
  val spd1 = addField("spd-1:")

  addSeparator
  val track2 = addField("track-2:")
  val pos2 = addField("pos-2:")
  val alt2 = addField("alt-2:")
  val hdg2 = addField("hdg-2:")
  val spd2 = addField("spd-2:")

  setContents

  def update (e: TrackPairEvent): Unit = {
    id.text = e.id
    etype.text = e.eventType
    details.text = e.eventDetails
    time.text = e.date.format_Hms

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

class TrackPairEventPanel(override val layer: TrackPairEventLayer)
                                       extends InteractiveLayerObjectPanel[TrackPairEventEntry,TrackPairEventFields](layer) {
  override def createFieldPanel: TrackPairEventFields = new GenericTrackPairEventFields

  def setEntry (e: TrackPairEventEntry): Unit = {
    entry = Some(e)
    fields.update(e.event)
  }
}

/**
  * the connector between the TrajectoryPairEvent and associated renderables
  */
class TrackPairEventEntry(val event: TrackPairEvent, val layer: TrackPairEventLayer)
                extends LayerObject with LayerSymbolOwner {

  protected var _isVisible = true
  protected var symbolShowing = false
  protected var marksShowing = false
  protected var pathsShowing = false  // only set if isVisible, depending on view level
  protected var contoursShowing = false

  val mark1Attrs = new PointPlacemarkAttributes
  val mark2Attrs = new PointPlacemarkAttributes

  val connectorAttrs = yieldInitialized(new BasicShapeAttributes) { attrs=>
    attrs.setOutlineWidth(1)
    attrs.setOutlineMaterial(lineMaterial)
    attrs.setEnableAntialiasing(true)
    attrs.setDrawInterior(false)
  }

  //--- the renderables that are associated with this entry
  protected var symbol: LayerSymbol = createSymbol
  protected var connector: Path = createConnector
  protected var posMarker1: PointPlacemark = createPos1Marker
  protected var posMarker2: PointPlacemark = createPos2Marker
  protected var path1:  TrajectoryPath = createPath1
  protected var path2:  TrajectoryPath = createPath2

  addSymbol

  //--- override in subclasses for specific rendering
  def createSymbol: LayerSymbol = new LayerSymbol(this)

  def createPos1Marker: PointPlacemark =
    createPosMarker(event.pos1, mark1Attrs, event.hdg1,
      layer.pos1Image(event), layer.pos1Label(event), layer.pos1LabelOffset(event), layer.track1Color(event))

  def createPos2Marker: PointPlacemark =
    createPosMarker(event.pos2, mark2Attrs, event.hdg2,
      layer.pos2Image(event), layer.pos2Label(event), layer.pos2LabelOffset(event), layer.track2Color(event))

  def createPosMarker(pos: GeoPosition, attrs: PointPlacemarkAttributes, heading: Angle,
                      getImage: =>Option[BufferedImage],
                      getLabelText: =>Option[String],
                      getLabelOffset: =>Offset,
                      getColor: =>Color ): PointPlacemark = {

    val p = new PointPlacemark(pos)
    getImage match {
      case Some(img:BufferedImage) =>
        attrs.setImage(img)
      case None =>
        attrs.setImage(null)
        attrs.setUsePointAsDefaultImage(true)
        attrs.setScale(5d)
    }
    getLabelText match {
      case Some(txt:String) =>
        attrs.setLabelOffset(getLabelOffset)
        p.setLabelText(txt)
      case None => p.setLabelText(null)
    }
    val material = new Material(getColor)
    attrs.setLabelMaterial(material)
    attrs.setLineMaterial(material)

    attrs.setImageOffset(Offset.CENTER)
    attrs.setHeading(heading.toDegrees)
    attrs.setHeadingReference(AVKey.RELATIVE_TO_GLOBE)

    p.setAttributes(attrs)
    p.setAltitudeMode(WorldWind.ABSOLUTE)
    p
  }

  def createConnector: Path = yieldInitialized(new Path(event.pos1, event.pos2)){ p=>
    p.setAttributes(connectorAttrs)
    p.setAltitudeMode(WorldWind.ABSOLUTE)
  }

  def createPath1: TrajectoryPath = createPath(event.trajectory1,mark1Attrs.getLabelMaterial,layer.showPathPositions)
  def createPath2: TrajectoryPath = createPath(event.trajectory2,mark2Attrs.getLabelMaterial,layer.showPathPositions)
  def createPath (traj: Trajectory, material: Material, showPositions: Boolean): TrajectoryPath = {
    val path = new TrajectoryPath(traj,material)
    path.setShowPositions(showPositions)
    path
  }

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
      layer.addRenderable(connector)
      marksShowing = true
    }
  }

  def removeMarks: Unit = {
    if (marksShowing) {
      layer.removeRenderable(posMarker1)
      layer.removeRenderable(posMarker2)
      layer.removeRenderable(connector)
      marksShowing = false
    }
  }

  def addPaths = {
    if (!pathsShowing) {
      //layer.addRenderable(posMarker1)
      layer.addRenderable(path1)
      //layer.addRenderable(posMarker2)
      layer.addRenderable(path2)
      //layer.addRenderable(connector)
      pathsShowing = true
    }
  }

  def removePaths = {
    if (pathsShowing) {
      //layer.removeRenderable(posMarker1)
      layer.removeRenderable(path1)
      //layer.removeRenderable(posMarker2)
      layer.removeRenderable(path2)
      //layer.removeRenderable(connector)
      pathsShowing = false
    }
  }

  def showContours = {
    if (pathsShowing && !contoursShowing) {
      path1.setContourAttrs(true)
      path2.setContourAttrs(true)
    }
    contoursShowing = true
  }

  def hideContours = {
    if (pathsShowing && contoursShowing) {
      path1.setContourAttrs(false)
      path2.setContourAttrs(false)
    }
    contoursShowing = false
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
      addMarks
      addSymbol
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
  override def symbolImage: BufferedImage = layer.symbolImage(event)
  override def symbolImageScale: Double = 1.0
  override def symbolHeading: Double = layer.symbolHeading(event)
  override def labelFont: Font = layer.labelFont
  override def subLabelFont: Font = layer.subLabelFont

  override def labelOffset: Offset = TrackSymbol.LabelOffset
  override def iconOffset: Offset = TrackSymbol.IconOffset
  override def wwPosition: WWPosition = {
    Position.fromDegrees(event.position.latDeg, event.position.lonDeg, event.position.altMeters)
  }
  override def displayName: String = s"${event.id}\n${event.date.format_Hms}\n${event.eventType}"

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
      case LayerObjectAttribute.Contour => if (cond) showContours else hideContours
      case LayerObjectAttribute.Info => // TBD
      case LayerObjectAttribute.Mark => // TDB
      case _ => // ignore
    }
  }

  override def isAttrSet(attr: LayerObjectAttribute): Boolean = {
    attr match {
      case LayerObjectAttribute.Path => pathsShowing
      case LayerObjectAttribute.Contour => contoursShowing
      case LayerObjectAttribute.Info => false
      case LayerObjectAttribute.Mark => false
      case _ => false
    }
  }
}

/**
  * a RACE layer to control display of TrajectoryPairEvents
  */
class TrackPairEventLayer(val raceViewer: RaceViewer, val config: Config)
              extends SubscribingRaceLayer
                with ConfigurableRenderingLayer
                with AltitudeSensitiveRaceLayer
                with InteractiveRaceLayer[TrackPairEventEntry] {

  val panel = createLayerInfoPanel
  val entryPanel = createEntryPanel

  val events = MutableMap[String,TrackPairEventEntry]()

  val iconLevel = new ThresholdLevel[TrackPairEventEntry](iconThresholdLevel)(setIconLevel)
  val labelLevel = new ThresholdLevel[TrackPairEventEntry](labelThresholdLevel)(setLabelLevel)
  val symbolLevels = new ThresholdLevelList(setDotLevel).sortIn(labelLevel,iconLevel)

  val showPathPositions: Boolean = config.getBooleanOrElse("show-positions", false)

  def defaultSymbolImage: BufferedImage = Images.getEventImage(color)

  def symbolImage (e: TrackPairEvent): BufferedImage = defaultSymbolImage
  def symbolHeading (e: TrackPairEvent): Double = 0

  def setDotLevel(e: TrackPairEventEntry): Unit = e.setDotLevel
  def setLabelLevel(e: TrackPairEventEntry): Unit = e.setLabelLevel
  def setIconLevel(e: TrackPairEventEntry): Unit = e.setIconLevel

  override def size: Int = events.size
  override def checkNewEyeAltitude: Unit = symbolLevels.triggerForEachValue(eyeAltitude,events)

  override def handleMessage: PartialFunction[Any, Unit] = {
    case BusEvent(_, e: TrackPairEvent, _) => updateEvents(e)
  }

  //--- track marker and path rendering attributes
  val track1Clr = config.getColorOrElse("track1-color", color)
  val track2Clr = config.getColorOrElse("track2-color", color)

  def pos1Image (e: TrackPairEvent): Option[BufferedImage] = Some(Images.getArrowImage(track1Clr))
  def pos2Image (e: TrackPairEvent): Option[BufferedImage] = Some(Images.getArrowImage(track2Clr))
  def pos1Label (e: TrackPairEvent): Option[String] = None
  def pos1LabelOffset (e: TrackPairEvent): Offset = Offset.fromFraction(0.5, 1.0)
  def pos2Label (e: TrackPairEvent): Option[String] = None
  def pos2LabelOffset (e: TrackPairEvent): Offset = Offset.fromFraction(0.5, -1.0)
  def track1Color (e: TrackPairEvent): Color = track1Clr
  def track2Color (e: TrackPairEvent): Color = track2Clr

  //--- InteractiveRaceLayer interface

  override def layerObjects: Iterable[TrackPairEventEntry] = events.values
  override def layerObjectQuery: Query[TrackPairEventEntry] = {
    new TrackPairEventQuery[TrackPairEventEntry](_.event)
  }

  override def maxLayerObjectRows: Int = config.getIntOrElse("max-rows", 10)

  override def layerObjectIdHeader: String = "event"
  override def layerObjectIdText (e: TrackPairEventEntry): String = e.id

  override def layerObjectDataHeader: String = "data"
  override def layerObjectDataText (e: TrackPairEventEntry): String = {
    val ev = e.event
    f"${ev.eventType}%10s  ${ev.eventDetails}%12s"
  }

  override def setLayerObjectAttribute(e: TrackPairEventEntry, attr: LayerObjectAttribute, cond: Boolean): Unit = {
    if (e.isAttrSet(attr) != cond) {
      e.setAttr(attr, cond)
      ifSome(LayerObjectAttribute.getAction(attr, cond)) { action =>
        raceViewer.objectChanged(e,action)
      }
    }
  }

  override def doubleClickLayerObject(e: TrackPairEventEntry): Unit = {
    //if (!entryPanel.isShowing(e)) {
      entryPanel.setEntry(e)
      raceViewer.setObjectPanel(entryPanel)
      raceViewer.objectChanged(e,LayerObjectAction.ShowPanel)
    //}
  }

  override def focusLayerObject(o: TrackPairEventEntry, cond: Boolean): Unit = {
    // TODO
  }

  override def dismissLayerObjectPanel (e: TrackPairEventEntry): Unit = {
    if (entryPanel.isShowing(e)) {
      entryPanel.reset

      raceViewer.dismissObjectPanel
      raceViewer.objectChanged(e, LayerObjectAction.DismissPanel)
    }
  }

  //--- initialization support - override if we need specialized entries/init
  protected def createEventEntry (event: TrackPairEvent): TrackPairEventEntry = {
    new TrackPairEventEntry(event, this)
  }

  protected def createLayerInfoPanel: InteractiveLayerInfoPanel[TrackPairEventEntry] = {
    new InteractiveLayerInfoPanel(this).styled("consolePanel")
  }

  protected def createEntryPanel: TrackPairEventPanel = new TrackPairEventPanel(this)



  def updateEvents(event: TrackPairEvent): Unit = {
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

    wwdRedrawManager.redraw()
    // the layerInfo panel does update periodically on its own
    //if (entryPanel.isShowing(entry)) entryPanel.update
  }

  override def selectObject(o: RaceLayerPickable, a: EventAction) = {
    o.layerItem match {
      case e: TrackPairEventEntry =>
        a match {
          case EventAction.LeftDoubleClick => doubleClickLayerObject(e)
          case _ => // ignored
        }
      case _ => // ignored
    }
  }
}
