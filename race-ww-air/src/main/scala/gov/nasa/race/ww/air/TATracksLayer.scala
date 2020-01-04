/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.ww.air

import java.awt.{Color, Font}

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{AirLocator, Airport, TATrack, TATracks, TRACON}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.GreatCircle
import gov.nasa.race.ifSome
import gov.nasa.race.swing._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.track.{TrackDropped, TrackTerminationMessage}
import gov.nasa.race.trajectory.MutTrajectory
import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww._
import gov.nasa.race.ww.track._
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render._


class TraconSymbol(val tracon: TRACON, val layer: TATracksLayer) extends PointPlacemark(wwPosition(tracon.position)) with RaceLayerPickable {
  var showDisplayName = false
  var attrs = new PointPlacemarkAttributes

  //setValue( AVKey.DISPLAY_NAME, tracon.id)
  setLabelText(tracon.id)
  setAltitudeMode(WorldWind.RELATIVE_TO_GROUND)
  attrs.setImage(null)
  attrs.setLabelColor(layer.traconLabelColor)
  attrs.setLineColor(layer.traconLabelColor)
  attrs.setUsePointAsDefaultImage(true)
  attrs.setScale(7d)
  setAttributes(attrs)

  override def layerItem: AnyRef = tracon
}

class TATrackEntry (_obj: TATrack, _trajectory: MutTrajectory, _layer: TATracksLayer) extends TrackEntry[TATrack](_obj,_trajectory,_layer) {

  override def setLabelLevel = symbol.foreach { sym =>
    sym.removeSubLabels
    sym.setLabelAttrs
  }

  override def setIconLevel = symbol.foreach { sym =>
    //sym.setSubLabelText(0,obj.stateString) // bug in MultiLabelPointPlacemark - it doesn't update the subLabel size
    sym.removeSubLabels
    sym.addSubLabelText(obj.stateString)
    sym.setIconAttrs
  }

  // we have a single sublabel line
  override def numberOfSublabels: Int = 1
  override def subLabelText (i: Int): String = if (i == 0) obj.stateString else null
}

/**
  * a layer to display TRACONs and related TATracks
  */
class TATracksLayer (val raceViewer: RaceViewer, val config: Config) extends ModelTrackLayer[TATrack] with AirLocator {

  //--- configured values

  val traconLabelColor = toABGRString(config.getColorOrElse("tracon-color", Color.white))
  var traconLabelThreshold = config.getDoubleOrElse("tracon-label-altitude", Meters(2200000.0).toMeters)
  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 5000000d)) // feet above ground
  val panDistance = config.getLengthOrElse("tracon-dist", NauticalMiles(200))
  val selectedOnly = config.getBooleanOrElse("selected-only", true)

  override def defaultColor = Color.green
  override def defaultSubLabelFont = new Font(Font.MONOSPACED,Font.PLAIN,scaledSize(11))

  override def defaultLabelThreshold = Meters(600000.0)
  override def defaultIconThreshold = Meters(200000.0)

  val traconGrid: Option[PolarGrid] =  createGrid

  var selTracon: Option[TRACON] = configuredTracon

  showTraconSymbols

  val selPanel = new AMOSelectionPanel[TRACON](
    "tracon:",
    "select tracon",
    TRACON.traconList,
    selTracon,
    _.id, _.name,
    Math.min(TRACON.traconList.size,40)
  )(processResult).defaultStyled
  panel.contents.insert(1, selPanel)

  override def initializeLayer: Unit = {
    super.initializeLayer
    selTracon.foreach(setTracon)
  }

  def configuredTracon: Option[TRACON] = {
    val topics = config.getOptionalStringList("request-topics")
    if (topics.nonEmpty) TRACON.tracons.get(topics.head) else None
  }

  override def createTrackEntry(track: TATrack) = new TATrackEntry(track,createTrajectory(track),this)

  def createGrid: Option[PolarGrid] = {
    if (config.getBooleanOrElse("show-tracon-grid", false)) {
      val gridRings = config.getIntOrElse("tracon-rings", 5)
      val gridRingDist = NauticalMiles(config.getIntOrElse("tracon-ring-inc", 50)) // in nm
      val gridLineColor = config.getColorOrElse("tracon-grid-color", Color.white)
      val gridLineAlpha = config.getFloatOrElse("tracon-grid-alpha", 0.5f)
      val gridFillColor = config.getColorOrElse("tracon-bg-color", Color.black)
      val gridFillAlpha = config.getFloatOrElse("tracon-bg-alpha", 0.4f)

      Some(new PolarGrid(TRACON.NoTracon.position, Angle.Angle0, gridRingDist, gridRings,
        this, gridLineColor, gridLineAlpha, gridFillColor, gridFillAlpha))
    } else None
  }

  // this is only called for non-filtered tracks
  protected def processTrack (track: TATrack): Unit = {
    incUpdateCount
    getTrackEntry(track) match {
      case Some(acEntry) =>
        if (!track.isDropped) updateTrackEntry(acEntry, track)
        else (removeTrackEntry(acEntry))
      case None => addTrackEntry(track)
    }
  }

  @inline def acceptSrc (src: String): Boolean = {
    !selectedOnly || (selTracon.isDefined && selTracon.get.id == src)
  }

  def handleTATracks (tracks: TATracks): Unit = {
    if (acceptSrc(tracks.assoc)) tracks.foreach (processTrack)
  }

  def handleTATrack (track: TATrack): Unit = {
    if (acceptSrc(track.src)) processTrack(track)
  }

  /**
    * this has to be implemented in the concrete RaceLayer. It is executed in the
    * event dispatcher
    */
  override def handleMessage: Receive = {
    case BusEvent(_, track: TATrack, _) => handleTATrack(track)
    case BusEvent(_, tracks: TATracks, _) => handleTATracks(tracks)
    case BusEvent(_, term: TrackTerminationMessage, _) =>
      trackEntries.get(term.id) match {
        case Some(acEntry) => removeTrackEntry(acEntry)
        case None => // nothing to drop
      }
  }

  def showTraconSymbol (tracon: TRACON) = {
    addRenderable(new TraconSymbol(tracon,this))
  }

  def showTraconSymbols = TRACON.traconList.foreach(showTraconSymbol)

  def selectTracon(tracon: TRACON) = raceViewer.trackUserAction(gotoTracon(tracon))

  def processResult (res: AMOSelection.Result[TRACON]): Unit = {
    res match {
      case AMOSelection.NoneSelected(_) => reset
      case AMOSelection.OneSelected(Some(tracon:TRACON)) => selectTracon(tracon)
      case _ => // ignore
    }
  }

  def reset(): Unit = {
    selTracon = None
    clearTrackEntries
    releaseAll
    ifSome(traconGrid){ _.hide}
    showTraconSymbols
  }

  def setTracon(tracon: TRACON) = {
    if (raceViewer.eyePosition != null) { // FIXME - we should always have an eyePosition here
      val eyePos = raceViewer.eyeLatLonPos
      val eyeAlt = Meters(eyeAltitude)
      val traconPos = tracon.position
      val viewCenterDist = GreatCircle.distance(eyePos, traconPos)

      if (eyeAlt > gotoAltitude) {
        if (viewCenterDist > panDistance) {
          raceViewer.panTo(wwPosition(traconPos), gotoAltitude.toMeters)
        } else {
          raceViewer.zoomTo(gotoAltitude.toMeters)
        }
      } else if (viewCenterDist > panDistance) {
        raceViewer.panTo(wwPosition(traconPos), gotoAltitude.toMeters)
      }
    }

    selTracon = Some(tracon)
    ifSome(traconGrid) { grid =>
      grid.setCenter(tracon.position)
      grid.show
    }
    requestTopic(selTracon)

    selPanel.updateSelection(selTracon)
  }

  def gotoTracon(tracon: TRACON) = {
    if (tracon == TRACON.NoTracon) {
      reset
    } else {
      selTracon match {
        case Some(`tracon`) => // nothing, we already show it
        case Some(lastTracon) =>
          reset
          setTracon(tracon)
        case None =>
          setTracon(tracon)
      }
    }
  }

  override def selectNonTrack(obj: RaceLayerPickable, action: EventAction): Unit = {
    obj.layerItem match {
      case tracon: TRACON =>
        action match {
          case EventAction.LeftClick =>
            selectTracon(tracon)
          case _ => // ignore
        }
      case _ => // ignore
    }
  }
}
