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
import gov.nasa.race.air.{AirLocator, Airport, TATrack, Tracon}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.ifSome
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.track.{TrackDropped, Trajectory}
import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww._
import gov.nasa.race.ww.track._
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render._


class TraconSymbol(val tracon: Tracon, val layer: TATracksLayer) extends PointPlacemark(wwPosition(tracon.position)) with RaceLayerPickable {
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

class TATrackEntry (_obj: TATrack, _trajectory: Trajectory, _layer: TATracksLayer) extends TrackEntry[TATrack](_obj,_trajectory,_layer) {

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

  override def defaultColor = Color.green
  override def defaultSubLabelFont = new Font(Font.MONOSPACED,Font.PLAIN,11)

  override def defaultLabelThreshold = Meters(600000.0)
  override def defaultIconThreshold = Meters(200000.0)

  val traconGrid: Option[PolarGrid] =  createGrid

  var selTracon: Option[Tracon] = configuredTracon

  showTraconSymbols

  def configuredTracon: Option[Tracon] = {
    val topics = config.getOptionalStringList("request-topics")
    if (topics.nonEmpty) Tracon.tracons.get(topics.head) else None
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

      Some(new PolarGrid(Tracon.NoTracon.position, Angle.Angle0, gridRingDist, gridRings,
        this, gridLineColor, gridLineAlpha, gridFillColor, gridFillAlpha))
    } else None
  }

  override def createLayerInfoPanel = {
    new TrackLayerInfoPanel(raceViewer,this){
      // insert tracon selection panel after generic layer info
      val choice = new StaticSelectionPanel[Tracon,IdAndNamePanel[Tracon]]("select TRACON",
        Tracon.NoTracon +: Tracon.traconList, 40, new IdAndNamePanel[Tracon]( _.id, _.name), selectTracon).styled()
      contents.insert(1, choice)
    }.styled('consolePanel)
  }

  /**
    * this has to be implemented in the concrete RaceLayer. It is executed in the
    * event dispatcher
    */
  override def handleMessage: Receive = {
    case BusEvent(_, track: TATrack, _) =>
      selTracon match {
        case Some(tracon) =>
          if (track.src == tracon.id) {
            incUpdateCount
            getTrackEntry(track) match {
              case Some(acEntry) =>
                if (!track.isDropped) updateTrackEntry(acEntry, track)
                else (removeTrackEntry(acEntry))
              case None => addTrackEntry(track)
            }
          }
        case None => // nothing selected, ignore
      }
    case BusEvent(_, drop: TrackDropped, _) =>
      trackEntries.get(drop.id) match {
        case Some(acEntry) => removeTrackEntry(acEntry)
        case None => // nothing to drop
      }
  }

  def showTraconSymbol (tracon: Tracon) = {
    addRenderable(new TraconSymbol(tracon,this))
  }

  def showTraconSymbols = Tracon.traconList.foreach(showTraconSymbol)

  def selectTracon(tracon: Tracon) = raceViewer.trackUserAction(gotoTracon(tracon))

  def reset(): Unit = {
    selTracon = None
    clearTrackEntries
    releaseAll
    ifSome(traconGrid){ _.hide}
    showTraconSymbols
  }

  def setTracon(tracon: Tracon) = {
    raceViewer.panTo(wwPosition(tracon.position), gotoAltitude.toMeters)

    selTracon = Some(tracon)
    ifSome(traconGrid) { grid =>
      grid.setCenter(tracon.position)
      grid.show
    }
    requestTopic(selTracon)
  }

  def gotoTracon(tracon: Tracon) = {
    if (tracon == Tracon.NoTracon) {
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
}
