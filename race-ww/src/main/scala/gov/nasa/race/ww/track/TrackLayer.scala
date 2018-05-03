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
import gov.nasa.race.common.{ThresholdLevel, ThresholdLevelList}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
import gov.nasa.race.swing.Style._
import gov.nasa.race.track._
import gov.nasa.race.util.StringUtils
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww.{AltitudeSensitiveRaceLayer, _}
import gov.nasa.worldwind.geom.Position

import scala.collection.mutable.{Map => MutableMap}
import scala.util.matching.Regex

object TrackLayer {

  //--- predefined actions (used for reference conparison - don't use literals instead)

  final val ShowPath = "ShowPath"
  final val HidePath = "HidePath"
  final val ShowContour = "ShowContour"
  final val HideContour = "HideContour"
  final val ShowInfo = "ShowInfo"
  final val HideInfo = "HideInfo"
  final val ShowMark = "ShowMark"
  final val HideMark = "HideMark"

}

/**
  * abstract layer class to display generic track objects
  *
  * Since the layer object owns the track collection and hence is responsible for track entry changes, it also has
  * to update/manage related panels (LayerInfo and TrackEntry)
  */
trait TrackLayer[T <:TrackedObject] extends SubscribingRaceLayer
                             with ConfigurableRenderingLayer with AltitudeSensitiveRaceLayer with TrackQueryContext {
  import TrackLayer._

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

  val noDisplayFilter: (TrackEntry[T])=>Boolean = (f) => true
  var displayFilter: (TrackEntry[T])=>Boolean = noDisplayFilter

  override def size = trackEntries.size

  //--- end ctor

  // override in subclasses for more specialized types
  protected def createLayerInfoPanel: TrackLayerInfoPanel[T] = new TrackLayerInfoPanel(raceViewer,this).styled('consolePanel)
  protected def createEntryPanel: TrackEntryPanel[T] = new TrackEntryPanel(raceViewer,this).styled('consolePanel)
  protected def createTrajectory(track: T) = new CompactTrajectory
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
  def queryLocation (id: String): Option[GeoPosition]

  //--- rendering detail level management


  def showPathPositions = showPositions && eyeAltitude < linePosLevel.upperBoundary


  def dismissEntryPanel (e: TrackEntry[T]) = {
    if (entryPanel.isShowing(e)) {
      raceViewer.dismissObjectPanel
      raceViewer.objectChanged(e, DismissPanel)
    }
  }

  def setDisplayFilter(filter: (TrackEntry[T])=>Boolean) = {
    displayFilter = filter
    if (filter eq noDisplayFilter) {
      trackEntries.foreach(_._2.show(true))
    } else {
      trackEntries.foreach { e => e._2.show(filter(e._2)) }
    }
    redraw
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
    panel.trySelectTrackEntry(e)
    raceViewer.objectChanged(e,Select)
  }

  def setTrackEntryPanel(e: TrackEntry[T]) = {
    if (!entryPanel.isShowing(e)) {
      entryPanel.setTrackEntry(e)
      raceViewer.setObjectPanel(entryPanel)
      raceViewer.objectChanged(e,ShowPanel)
    }
  }

  //--- create, update and remove TrackEntries

  def addTrackEntryAttributes(e: TrackEntry[T]): Unit = e.addRenderables
  def updateTrackEntryAttributes(e: TrackEntry[T]): Unit = e.updateRenderables
  def releaseTrackEntryAttributes(e: TrackEntry[T]): Unit = e.removeRenderables

  protected def addTrackEntry(track: T) = {
    val e = createTrackEntry(track)
    symbolLevels.triggerInCurrentLevel(e)

    trackEntries += (getTrackKey(track) -> e)
    // ?? should we also add the entry under the cross-channel 'cs' key ??

    if (displayFilter(e)) {
      // ad hoc track display
      if (StringUtils.matchesAny(track.cs, pathTrackFilter)) setPath(e,true)
      if (StringUtils.matchesAny(track.cs, infoTrackFilter)) setInfo(e,true)
      if (StringUtils.matchesAny(track.cs, focusTrackFilter)) setFocused(e,true)

      addTrackEntryAttributes(e)
      wwdRedrawManager.redraw()
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
          GreatCircle.translate(raceViewer.eyeLatLonPos, lastObj.position,obj.position)
        }
        val dAlt = obj.altitude - lastObj.altitude
        //raceViewer.eyePositionTo(new Position(ep,(raceViewer.eyeAltitude + dAlt).toMeters), 750)
        raceViewer.setEyePosition(new Position(ep,(raceViewer.eyeAltitude + dAlt).toMeters))
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
  }


  //--- track entry attribute management. Note we have to guard against recursive calls by checking for attr change

  override def changeObject(objectId: String, action: String) = {
    ifSome(trackEntries.get(objectId)) { e =>
      action match {
        case `Select`       => selectTrackEntry(e)
        case `ShowPanel`    => setTrackEntryPanel(e)
        case `DismissPanel` => dismissEntryPanel(e)

        case `StartFocus`   => setFocused(e,true)
        case `StopFocus`    => setFocused(e,false)
        case `ShowPath`     => setPath(e,true)
        case `HidePath`     => setPath(e,false)
        case `ShowContour`  => setPathContour(e,true)
        case `HideContour`  => setPathContour(e,false)
        case `ShowInfo`     => setInfo(e,true)
        case `HideInfo`     => setInfo(e,false)
        case `ShowMark`     => setMark(e,true)
        case `HideMark`     => setMark(e,false)
        case _ => // ignore
      }
    }
  }

  override def setFocused (lo: LayerObject, cond: Boolean, report: Boolean = true): Unit = {
    setLayerObjectAttribute(lo, _.isFocused != cond){ e=>
      e.setFocused(cond)
      if (report) raceViewer.setFocused(e, cond) // report upwards in the chain
      if (cond) {
        raceViewer.panToCenter(lo.pos)
        raceViewer.objectChanged(lo,StartFocus)
      } else {
        raceViewer.objectChanged(lo,StopFocus)
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
            panel.updateTrackEntryAttributes // update layerinfo panel
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

  override def handleMessage = {
    case BusEvent(_,fInfo:TrackInfo,_) => entryPanel.setTrackInfo(fInfo)
    case BusEvent(_,csChange:TrackCsChanged,_) => // todo
    case DelayedAction(_,action) => action()
  }
}


