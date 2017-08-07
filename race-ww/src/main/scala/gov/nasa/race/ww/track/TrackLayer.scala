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

import java.awt.Color

import com.github.nscala_time.time.Imports._
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.Threshold
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track._
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.track.TrackPathRenderLevel.TrackPathRenderLevel
import gov.nasa.race.ww.track.TrackRenderLevel.TrackRenderLevel
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww._

import scala.collection.mutable.{Map => MutableMap}


/**
  * abstract layer class to display aircraft in flight
  */
abstract class TrackLayer[T <:TrackedObject](val raceView: RaceView, config: Config)
                                  extends SubscribingRaceLayer(raceView,config)
                                     with DynamicRaceLayerInfo with AltitudeSensitiveLayerInfo with TrackQueryContext {

  val panel = createLayerInfoPanel
  val entryPanel = createEntryPanel

  val trackInfoBase = config.getOptionalString("trackinfo-base")

  //--- AircraftPlacemark attributes
  def defaultSymbolColor = Color.cyan
  val color = config.getColorOrElse("color", defaultSymbolColor)

  def defaultSymbolImage = Images.getArrowImage(color)
  val symbolImg = defaultSymbolImage

  val markImg = Images.defaultMarkImg
  val labelColor = config.getColorOrElse("label-color",color)
  val labelColorString = toABGRString(labelColor)

  def defaultLabelFont = raceView.defaultLabelFont
  val labelFont = config.getFontOrElse("label-font", defaultLabelFont)
  def defaultSubLabelFont = raceView.defaultSubLabelFont
  val subLabelFont = config.getOptionalFont("sublabel-font").orElse(defaultSubLabelFont)
  val lineColor = toABGRString(config.getColorOrElse("line-color",color))
  def defaultLabelThreshold = Meters(1400000.0).toMeters
  var labelThreshold = config.getDoubleOrElse("label-altitude", defaultLabelThreshold)
  def defaultSymbolThreshold = Meters(1000000.0).toMeters
  var symbolThreshold = config.getDoubleOrElse("symbol-altitude", defaultSymbolThreshold)
  var trackDetails: TrackRenderLevel = getTrackRenderLevel(eyeAltitude)

  def image (t: T) = symbolImg
  def markImage (t: T) = markImg

  //--- AircraftPath attributes
  val pathColor = color
  val showPositions = config.getBooleanOrElse("show-positions", true)
  val linePosThreshold = config.getDoubleOrElse("position-altitude", Meters(30000.0).toMeters)
  var pathDetails: TrackPathRenderLevel = getPathRenderLevel(eyeAltitude)

  thresholds ++= Seq(
    new Threshold(linePosThreshold, setLinePosLevel, setLineLevel),
    new Threshold(symbolThreshold, setSymbolLevel, setLabelLevel),
    new Threshold(labelThreshold,  setLabelLevel, setDotLevel)
  )

  //--- the data we manage
  val trackEntries = MutableMap[String,TrackEntry[T]]()

  val noDisplayFilter: (TrackEntry[T])=>Boolean = (f) => true
  var displayFilter: (TrackEntry[T])=>Boolean = noDisplayFilter

  override def size = trackEntries.size

  //--- end ctor

  // override for specialized LayerInfoPanel
  protected def createLayerInfoPanel: TrackLayerInfoPanel[T] = new TrackLayerInfoPanel(raceView,this).styled('consolePanel)
  protected def createEntryPanel: TrackEntryPanel[T] = new TrackEntryPanel(raceView,this).styled('consolePanel)

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

  override def queryDate = raceView.updatedSimTime

  override def reportQueryError (msg: String) = error(msg)

  // layer specific positions (cities, airports, ports etc.) - TODO - should default at least to cities and airports here
  override def queryLocation (id: String): Option[GeoPosition]

  //--- rendering detail level management

  def getTrackRenderLevel(alt: Double) = {
    if (alt > labelThreshold) TrackRenderLevel.Dot
    else if (alt > symbolThreshold) TrackRenderLevel.Label
    else TrackRenderLevel.Symbol
  }
  def setTrackRenderLevel(level: TrackRenderLevel, f: (TrackEntry[T])=>Unit): Unit = {
    trackDetails = level
    trackEntries.foreach(e=> f(e._2))
    redrawNow
  }

  def setDotLevel    = setTrackRenderLevel( TrackRenderLevel.Dot, (e)=> e.setDotLevel)
  def setLabelLevel  = setTrackRenderLevel( TrackRenderLevel.Label, (e)=> e.setLabelLevel)
  def setSymbolLevel = setTrackRenderLevel( TrackRenderLevel.Symbol, (e)=> e.setSymbolLevel)

  def setTrackLevel(e: TrackEntry[T]) = {
    trackDetails match {
      case TrackRenderLevel.Dot => e.setDotLevel
      case TrackRenderLevel.Label => e.setLabelLevel
      case TrackRenderLevel.Symbol => e.setSymbolLevel
    }
  }

  def getPathRenderLevel (alt: Double) = if (alt > linePosThreshold) TrackPathRenderLevel.Line else TrackPathRenderLevel.LinePos
  def setPathRenderLevel (level: TrackPathRenderLevel,f: (TrackEntry[T])=>Unit): Unit = {
    pathDetails = level
    trackEntries.foreach(e=> f(e._2))
    redrawNow
  }
  def setLineLevel = {
    setSymbolLevel
    setPathRenderLevel( TrackPathRenderLevel.Line, (e)=> e.setLineLevel)
  }
  def setLinePosLevel = {
    setSymbolLevel
    setPathRenderLevel( TrackPathRenderLevel.LinePos, (e)=> e.setLinePosLevel)
  }

  def showPathPositions = showPositions && eyeAltitude < linePosThreshold

  def createFlightPath (fpos: T) = new CompactTrajectory

  def getSymbol (e: TrackEntry[T]): Option[TrackSymbol[T]] = Some(new TrackSymbol(e))

  // this is here so that specialized FlightLayers can select the label text and set sublabels
  def setLabel (sym: TrackSymbol[T]) = {
    val track = sym.trackEntry.obj
    sym.setLabelText( if (track.cs != track.id) track.cs else track.id)
  }
  def updateLabel (sym: TrackSymbol[T]) = {} // override if label text is dynamic

  def dismissEntryPanel (e: TrackEntry[T]) = {
    if (entryPanel.isShowing(e)) {
      raceView.dismissObjectPanel
      raceView.objectChanged(e, DismissPanel)
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

  def changeObjectAttr(e: TrackEntry[T], f: =>Unit, action: String) = {
    f
    changedTrackEntryOptions(e,action)
    redraw
  }

  //--- layer object sync actions
  final val StartCenter = "StartCenter"
  final val StopCenter = "StopCenter"
  final val ShowPath = "ShowPath"
  final val HidePath = "HidePath"
  final val ShowInfo = "ShowInfo"
  final val HideInfo = "HideInfo"
  final val ShowMark = "ShowMark"
  final val HideMark = "HideMark"

  override def changeObject(objectId: String, action: String) = {
    ifSome(trackEntries.get(objectId)) { e =>
      action match {
          //--- generic ones
        case `Select`       => selectTrackEntry(e)
        case `ShowPanel`    => setTrackEntryPanel(e)
        case `DismissPanel` => dismissEntryPanel(e)
          //--- our own ones
        case `StartCenter` => startCenteringTrackEntry(e)
        case `StopCenter` => ifSome(centeredEntry) { ce=> if (ce eq e) stopCenteringTrackEntry }
        case `ShowPath`   => changeObjectAttr(e, e.setPath(true), action)
        case `HidePath`   => changeObjectAttr(e, e.setPath(false), action)
        case `ShowInfo`   => changeObjectAttr(e, e.setInfo(true), action)
        case `HideInfo`   => changeObjectAttr(e, e.setInfo(false), action)
        case `ShowMark`   => changeObjectAttr(e, e.setMark(true), action)
        case `HideMark`   => changeObjectAttr(e, e.setMark(false), action)
        case other => // ignore
      }
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
    panel.trySelectFlightEntry(e)
    raceView.objectChanged(e,Select)
  }

  def setTrackEntryPanel(e: TrackEntry[T]) = {
    if (!entryPanel.isShowing(e)) {
      entryPanel.setTrackEntry(e)
      raceView.setObjectPanel(entryPanel)
      raceView.objectChanged(e,ShowPanel)
    }
  }

  //--- create, update and remove TrackEntries

  // here so that it can be overridden by subclasses, which can by useful in case the layer has to manage
  // resources that are display relevant for FlightEntries (such as 3D models)
  protected def createTrackEntry(fpos: T): TrackEntry[T] = new TrackEntry[T](fpos,createFlightPath(fpos), this)

  def addTrackEntryAttributes(e: TrackEntry[T]): Unit = e.addRenderables
  def updateTrackEntryAttributes(e: TrackEntry[T]): Unit = e.updateRenderables
  def releaseTrackEntryAttributes(e: TrackEntry[T]): Unit = e.removeRenderables

  protected def addTrackEntry(track: T) = {
    val e = createTrackEntry(track)
    trackEntries += (getTrackKey(track) -> e)
    // ?? should we also add the entry under the cross-channel 'cs' key ??

    if (displayFilter(e)) {
      addTrackEntryAttributes(e)
      wwdRedrawManager.redraw()
    }
    // we don't add to the panel here since it might have an active query and the new entry might not match
  }

  protected def updateTrackEntry(e: TrackEntry[T], fpos: T) = {
    if (e.obj.date < fpos.date) { // don't overwrite new with old data
      e.setNewObj(fpos)
      updateTrackEntryAttributes(e)
      if (e.hasSymbol) wwdRedrawManager.redraw()
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

  //--- track entry centering

  var centeredEntry: Option[TrackEntry[T]] = None

  def centerEntry (e: TrackEntry[T]) = {
    if (e.hasAssignedModel) {
      raceView.centerOn(e.obj)
    } else {
      raceView.panToCenter(e.obj)
    }
  }

  def startCenteringTrackEntry(e: TrackEntry[T]) = {
    ifSome(centeredEntry){_.followPosition(false)}
    e.followPosition(true)
    raceView.panToCenter(e.obj)
    centeredEntry = Some(e)
    changedTrackEntryOptions(e,StartCenter)
  }
  def stopCenteringTrackEntry = {
    ifSome(centeredEntry) { e =>
      e.followPosition(false)
      centeredEntry = None
      changedTrackEntryOptions(e,StopCenter)
    }
  }

  def changedTrackEntryOptions(e: TrackEntry[T], action: String) = {
    panel.changedFlightEntryOptions
    entryPanel.changedTrackEntryOptions
    raceView.objectChanged(e,action)
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
    case DelayedAction(_,action) => action()
  }


  //--- track query interface


}


