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

package gov.nasa.race.ww.track

import java.awt.image.BufferedImage

import com.github.nscala_time.time.Imports._
import gov.nasa.race._
import gov.nasa.race.common.{Query, ThresholdLevel, ThresholdLevelList}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.geo.{GeoPositioned, GreatCircle}
import gov.nasa.race.swing.Style._
import gov.nasa.race.track.{TrackTerminationMessage, _}
import gov.nasa.race.trajectory.{MutCompressedTrajectory, MutTrajectory}
import gov.nasa.race.util.{DateTimeUtils, StringUtils}
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.LayerObjectAction.LayerObjectAction
import gov.nasa.race.ww.LayerObjectAttribute.LayerObjectAttribute
import gov.nasa.race.ww.{AltitudeSensitiveRaceLayer, _}
import gov.nasa.worldwind.geom.Position

import scala.collection.mutable.{Map => MutableMap}
import scala.util.matching.Regex


/**
  * abstract layer class to display generic track objects
  *
  * Since the layer object owns the track collection and hence is responsible for track entry changes,
  * it also has to update/manage related panels (LayerInfo (with list view), TrackEntry)
  */
trait TrackLayer[T <:TrackedObject] extends SubscribingRaceLayer
                             with ConfigurableRenderingLayer
                             with AltitudeSensitiveRaceLayer
                             with InteractiveRaceLayer[TrackEntry[T]]
                             with TrackQueryContext {

  val panel = createLayerInfoPanel
  val entryPanel = createEntryPanel

  val trackInfoBase = config.getOptionalString("trackinfo-base")

  //--- rendering attributes

  // layer global rendering resources
  def defaultSymbolImg: BufferedImage = Images.getArrowImage(color)
  val symbolImg = defaultSymbolImg

  def defaultMarkImg: BufferedImage = Images.defaultMarkImg
  val markImg = defaultMarkImg


  //--- symbol levels (with different rendering attributes)
  val iconLevel = new ThresholdLevel[TrackEntry[T]](iconThresholdLevel)(setIconLevel)
  val labelLevel = new ThresholdLevel[TrackEntry[T]](labelThresholdLevel)(setLabelLevel)
  val symbolLevels = new ThresholdLevelList(setDotLevel).sortIn(labelLevel,iconLevel)

  def setDotLevel(e: TrackEntry[T]): Unit = e.setDotLevel
  def setLabelLevel(e: TrackEntry[T]): Unit = e.setLabelLevel
  def setIconLevel(e: TrackEntry[T]): Unit = e.setIconLevel

  //--- path levels
  val lineLevel = new ThresholdLevel[TrackEntry[T]](lineThresholdLevel)(setLineLevel)
  val linePosLevel = new ThresholdLevel[TrackEntry[T]](linePosThresholdLevel)(setLinePosLevel)
  val pathLevels = new ThresholdLevelList(setNoLineLevel).sortIn(lineLevel,linePosLevel)

  def setLineLevel(e: TrackEntry[T]) = e.setLineLevel
  def setLinePosLevel(e: TrackEntry[T]) = e.setLinePosLevel
  def setNoLineLevel(e: TrackEntry[T]) = e.setNoLineLevel

  override def initEyeAltitude = symbolLevels.setCurrentLevel(eyeAltitude)

  override def checkNewEyeAltitude = {
    symbolLevels.triggerForEachValue(eyeAltitude,trackEntries)
    pathLevels.triggerForEachValue(eyeAltitude,trackEntries)
  }

  val showPositions = config.getBooleanOrElse("show-positions", true) // when paths are drawn

  //--- optionally configured track id filters for which we automatically set track attributes
  val pathTrackFilter = config.getStringArray("show-paths").map(new Regex(_)).toSeq
  val infoTrackFilter = config.getStringArray("show-infos").map(new Regex(_)).toSeq
  val focusTrackFilter = config.getStringArray("focus").map(new Regex(_)).toSeq

  //--- the data we manage
  val trackEntries = MutableMap[String,TrackEntry[T]]()

  override def size = trackEntries.size

  //--- InteractiveRaceLayer interface

  override def layerObjects: Iterable[TrackEntry[T]] = trackEntries.values
  override def layerObjectQuery: Query[TrackEntry[T]] = new TrackQuery(this, _.obj)

  override def maxLayerObjectRows: Int = config.getIntOrElse("max-rows", 10)

  override def layerObjectIdHeader: String = "c/s"
  override def layerObjectIdText (e: TrackEntry[T]): String = e.id

  override def layerObjectDataHeader: String = "    time   [ft] hdg [kn]"
                                             //"--:--:-- ------ --- ----"
  override def layerObjectDataText (e: TrackEntry[T]): String = {
    val obj = e.obj
    val dtg = DateTimeUtils.formatDate(obj.date, DateTimeUtils.hhmmss)
    val alt = obj.position.altitude.toFeet.toInt
    val hdg = obj.heading.toNormalizedDegrees.toInt
    val spd = obj.speed.toKnots.toInt
    f"$dtg $alt%6d $hdg%3d $spd%4d"
  }

  override def setLayerObjectAttribute(o: TrackEntry[T], attr: LayerObjectAttribute, cond: Boolean): Unit = {
    if (o.isAttrSet(attr) != cond) {
      o.setAttr(attr, cond)
      ifSome(LayerObjectAttribute.getAction(attr, cond)) { action =>
        raceViewer.objectChanged(o,action)
      }
      panel.updateEntryAttributes // update layerinfo panel
      entryPanel.updateTrackEntryAttributes // update entry panel
      redraw
    }
  }

  override def doubleClickLayerObject(e: TrackEntry[T]): Unit = setTrackEntryPanel(e)

  //--- initialization support - override in subclasses for more specialized types

  protected def createLayerInfoPanel: InteractiveLayerInfoPanel[TrackEntry[T]] = {
    new InteractiveLayerInfoPanel[TrackEntry[T]](this).styled('consolePanel)
  }

  protected def createEntryPanel: TrackEntryPanel[T] = new TrackEntryPanel(raceViewer,this).styled('consolePanel)

  protected def createTrajectory(track: T): MutTrajectory = new MutCompressedTrajectory(50)

  protected def createTrackEntry(track: T): TrackEntry[T] = new TrackEntry[T](track,createTrajectory(track), this)


  def matchingTracks(f: TrackEntry[T]=>Boolean): Seq[TrackEntry[T]] = {
    trackEntries.foldLeft(Seq.empty[TrackEntry[T]])((acc, e) => {
      val flight = e._2
      if (f(flight)) flight +: acc else acc
    })
  }

  def foreachTrack(f: TrackEntry[T]=>Unit): Unit = trackEntries.foreach(e=> f(e._2))

  //--- override if tracks are stored under a different key
  //    (make sure to keep getTrack and queryTrack consistent)

  def getTrackKey(track: T): String = track.id

  def getTrackEntry(track: T): Option[TrackEntry[T]] = trackEntries.get(getTrackKey(track))

  override def queryTrack(key: String): Option[TrackedObject] = {
    trackEntries.get(key).orElse(trackEntries.valuesIterator.find(e => e.obj.cs == key)).map( _.obj )
  }

  override def queryDate = raceViewer.updatedSimTime

  override def reportQueryError (msg: String) = error(msg)

  // layer specific positions (cities, airports, ports etc.) - TODO - should default at least to cities and airports here
  def queryLocation (id: String): Option[GeoPositioned]


  //--- rendering detail level management


  def showPathPositions = showPositions && eyeAltitude < linePosLevel.upperBoundary


  def dismissEntryPanel (e: TrackEntry[T]) = {
    if (entryPanel.isShowing(e)) {
      raceViewer.dismissObjectPanel
      raceViewer.objectChanged(e, LayerObjectAction.DismissPanel)
    }
  }

  /**
    * the pick handler interface
    * Note we already know this object belongs to this layer
    */
  override def selectObject(o: RaceLayerPickable, a: EventAction) = {
    o.layerItem match {
      case e: TrackEntry[T] =>
        a match {
          case EventAction.LeftClick => selectTrackEntry(e)
          case EventAction.LeftDoubleClick => setTrackEntryPanel(e)
          case other => // ignored
        }
      case other => // ignored
    }
  }

  def selectTrackEntry(e: TrackEntry[T]) = {
    panel.trySelectEntry(e)
    raceViewer.objectChanged(e,LayerObjectAction.Select)
  }

  def setTrackEntryPanel(e: TrackEntry[T]) = {
    if (!entryPanel.isShowing(e)) {
      entryPanel.setTrackEntry(e)
      raceViewer.setObjectPanel(entryPanel)
      raceViewer.objectChanged(e,LayerObjectAction.ShowPanel)
    }
  }

  //--- create, update and remove TrackEntries

  def handleTrack (obj: T): Unit = {
    incUpdateCount

    getTrackEntry(obj) match {
      case Some(acEntry) =>
        if (obj.isDroppedOrCompleted) removeTrackEntry(acEntry) else updateTrackEntry(acEntry, obj)

      case None => addTrackEntry(obj)
    }
  }

  def handleTermination (msg: TrackTerminationMessage): Unit  = {
    incUpdateCount
    ifSome(trackEntries.get(msg.cs)) { removeTrackEntry }
  }

  def addTrackEntryAttributes(e: TrackEntry[T]): Unit = e.addRenderables
  def updateTrackEntryAttributes(e: TrackEntry[T]): Unit = e.updateRenderables
  def releaseTrackEntryAttributes(e: TrackEntry[T]): Unit = e.removeRenderables

  def checkVisibility (e: TrackEntry[T]): Boolean = displayFilter.isEmpty || displayFilter.get(e)

  protected def addTrackEntry(track: T) = {
    val e = createTrackEntry(track)
    symbolLevels.triggerInCurrentLevel(e)

    trackEntries += (getTrackKey(track) -> e)
    // ?? should we also add the entry under the cross-channel 'cs' key ??

    if (checkVisibility(e)) {
      // ad hoc track display
      if (StringUtils.matchesAny(track.cs, pathTrackFilter)) setPath(e,true)
      if (StringUtils.matchesAny(track.cs, infoTrackFilter)) setInfo(e,true)
      if (StringUtils.matchesAny(track.cs, focusTrackFilter)) setFocused(e,true)

      addTrackEntryAttributes(e)
      wwdRedrawManager.redraw()

    } else {
      e.setVisible(false)
    }
    // we don't add to the panel here since it might have an active query and the new entry might not match
  }

  protected def updateTrackEntry(e: TrackEntry[T], obj: T) = {
    val lastObj = e.obj
    if (lastObj.date < obj.date) { // don't overwrite new with old data
      e.setNewObj(obj)
      if (e.isFocused) {
        val ep = if (raceViewer.isOrthgonalView) obj.position else {
          // keep eye altitude, view pitch and heading, translate eye position
          GreatCircle.translate(raceViewer.tgtEyePos, lastObj.position,obj.position)
        }
        val dAlt = (obj.position.altitude - lastObj.position.altitude).toMeters
        raceViewer.jumpToEyePosition(Position.fromDegrees(ep.latDeg, ep.lonDeg, raceViewer.tgtZoom + dAlt))
        //raceViewer.panToCenter(wwPosition(ep, Meters(raceViewer.tgtZoom + dAlt)),500) // FIXME
      }
      updateTrackEntryAttributes(e)
      if (e.hasSymbol) wwdRedrawManager.redraw()
      // the layerInfo panel does update periodically on its own
      if (entryPanel.isShowing(e)) entryPanel.update
    }
  }

  protected def removeTrackEntry(e: TrackEntry[T]) = {
    val wasShowing = e.hasSymbol
    releaseTrackEntryAttributes(e)
    trackEntries -= getTrackKey(e.obj)
    if (wasShowing) wwdRedrawManager.redraw()
    panel.removedEntry(e)
    if (entryPanel.isShowing(e)) entryPanel.update
  }

  protected def clearTrackEntries = {
    trackEntries.clear
    removeAllRenderables()
    wwdRedrawManager.redraw()
    panel.removedAllEntries
  }


  //--- track entry attribute management. Note we have to guard against recursive calls by checking for attr change

  override def changeObject(objectId: String, action: LayerObjectAction) = {
    ifSome(trackEntries.get(objectId)) { e =>
      action match {
        case LayerObjectAction.Select       => selectTrackEntry(e)
        case LayerObjectAction.ShowPanel    => setTrackEntryPanel(e)
        case LayerObjectAction.DismissPanel => dismissEntryPanel(e)
        case LayerObjectAction.StartFocus   => setFocused(e,true)
        case LayerObjectAction.StopFocus    => setFocused(e,false)

        case LayerObjectAction.ShowPath     => setPath(e,true)
        case LayerObjectAction.HidePath     => setPath(e,false)
        case LayerObjectAction.ShowContour  => setPathContour(e,true)
        case LayerObjectAction.HideContour  => setPathContour(e,false)
        case LayerObjectAction.ShowInfo     => setInfo(e,true)
        case LayerObjectAction.HideInfo     => setInfo(e,false)
        case LayerObjectAction.ShowMark     => setMark(e,true)
        case LayerObjectAction.HideMark     => setMark(e,false)
        case _ => // ignore
      }
    }
  }

  override def setFocused (lo: LayerObject, cond: Boolean, report: Boolean = true): Unit = {
    setLayerObjectAttribute(lo, _.isFocused != cond){ e=>
      e.setFocused(cond)
      if (report) raceViewer.setFocused(e, cond) // report upwards in the chain
      if (cond) {
        val loPos = lo.pos.position
        raceViewer.centerTo(Position.fromDegrees(loPos.latDeg, loPos.lonDeg, raceViewer.tgtZoom))  // FIXME
        raceViewer.objectChanged(lo,LayerObjectAction.StartFocus)
      } else {
        raceViewer.objectChanged(lo,LayerObjectAction.StopFocus)
      }
    }
  }

  def setPath (lo: LayerObject, cond: Boolean): Unit = {
    setLayerObjectAttribute(lo, _.hasPath != cond) { _.setPath(cond) }
  }

  def setPathContour(lo: LayerObject, cond: Boolean): Unit = {
    setLayerObjectAttribute(lo, _.drawPathContour != cond) { _.setPathContour(cond) }
  }

  def setInfo (lo: LayerObject, cond: Boolean): Unit = {
    setLayerObjectAttribute(lo, _.hasInfo != cond) { _.setInfo(cond) }
  }

  def setMark (lo: LayerObject, cond: Boolean): Unit = {
    setLayerObjectAttribute(lo, _.hasMark != cond) { _.setMark(cond) }
  }

  protected def setLayerObjectAttribute (lo: LayerObject, guard: (TrackEntry[T])=>Boolean)(action: (TrackEntry[T])=>Unit): Unit = {
    lo match {
      case e: TrackEntry[T] =>
        if (e.layer == this) {
          if (guard(e)) {
            action(e)
            panel.updateEntryAttributes // update layerinfo panel
            entryPanel.updateTrackEntryAttributes // update entry panel
            redraw // ?? do we need this
          }
        } else warning(s"can't set attribute for foreign layer object $e")
      case o => warning(s"not a layer object $o")
    }
  }


  def requestTrackInfoUpdates (e: TrackEntry[T]) = {
    ifSome(trackInfoBase) { baseChannel =>
      val cs = e.obj.cs
      val channel = s"$baseChannel/$cs"
      actor.subscribe(channel)
      request(channel, Some(TrackInfoUpdateRequest(cs)))
    }
  }
  def releaseTrackInfoUpdates (e: TrackEntry[T]) = {
    ifSome(trackInfoBase) { baseChannel =>
      val cs = e.obj.cs
      val channel = s"$baseChannel/$cs"
      actor.unsubscribe(channel)
      release(channel, Some(TrackInfoUpdateRequest(cs)))
    }
  }

  override def handleMessage: PartialFunction[Any, Unit] = {
    case BusEvent(_,fInfo:TrackInfo,_) => entryPanel.setTrackInfo(fInfo)
    case BusEvent(_,csChange:TrackCsChanged,_) => // todo
    case DelayedAction(_,action) => action()
  }
}


