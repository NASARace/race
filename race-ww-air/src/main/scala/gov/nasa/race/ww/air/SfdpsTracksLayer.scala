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
package gov.nasa.race.ww.air

import java.awt.Color
import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{ARTCC, SfdpsTrack, SfdpsTracks}
import gov.nasa.race.common.AllId
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.geo.GeoPositioned
import gov.nasa.race.swing._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{MultiSelection, MultiSelectionPanel}
import gov.nasa.race.track.{TrackCompleted, TrackDropped}
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww.track.ModelTrackLayer
import gov.nasa.race.ww.{EventAction, Images, RaceLayerPickable, RaceViewer, toABGRString, wwPosition}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render.{PointPlacemark, PointPlacemarkAttributes}

import scala.collection.Seq

class ArtccSymbol (val artcc: ARTCC, val layer: SfdpsTracksLayer) extends PointPlacemark(wwPosition(artcc.position)) with RaceLayerPickable {
  var showDisplayName = false
  var attrs = new PointPlacemarkAttributes

  //setValue( AVKey.DISPLAY_NAME, tracon.id)
  setLabelText(artcc.id)
  setAltitudeMode(WorldWind.RELATIVE_TO_GROUND)
  attrs.setImage(null)
  attrs.setLabelFont(layer.labelFont)
  attrs.setLabelColor(layer.locationColor)
  attrs.setLineColor(layer.locationColor)
  attrs.setUsePointAsDefaultImage(true) // we should use a different default image
  attrs.setScale(scaledSize(7).toDouble)
  setAttributes(attrs)

  override def layerItem: AnyRef = artcc
}


/**
  * a TrackLayer for SFDPS (en route) SWIM data that allows selection of displayed ARTCCs
  * (use generic AircraftLayer if we want to display all)
  */
class SfdpsTracksLayer(val raceViewer: RaceViewer, val config: Config) extends ModelTrackLayer[SfdpsTrack] {

  val showLocations = config.getBooleanOrElse("show-locations", true)
  val locationColor = toABGRString(config.getColorOrElse("location-color", Color.magenta))
  var artccLabelThreshold = config.getDoubleOrElse("artcc-label-altitude", Meters(2200000.0).toMeters)
  val selectedOnly = config.getBooleanOrElse("selected-only", true)

  override def defaultColor = Color.red
  override def defaultSymbolImg = Images.getPlaneImage(color)
  override def getTrackKey(track: SfdpsTrack): String = track.cs
  override def queryLocation(id: String): Option[GeoPositioned] = ARTCC.artccs.get(id)

  var selARTCCs = Seq.empty[ARTCC]

  val selPanel = new MultiSelectionPanel[ARTCC](
    "artcc:", "Select ARTCCs",
    ARTCC.artccList, selectedARTCCs, _.id, _.name, // those are functions
    ARTCC.artccList.size
  )( selectARTCCs // the selection action
  ).defaultStyled
  panel.contents.insert(1, selPanel)

  if (showLocations) showArtccSymbols

  //--- end init

  override def mapTopic (to: Option[AnyRef]): Option[AnyRef] = {
    to match {
      case Some(id: String) =>
        if (ARTCC.artccs.contains(id)) {
          val artcc = ARTCC.artccs(id)
          selARTCCs = selARTCCs :+ artcc
          selPanel.updateSelection(selARTCCs)
          Some(artcc)
        } else {
          if (id == AllId){
            selARTCCs = Seq(ARTCC.AnyARTCC)
            selPanel.updateSelection(selARTCCs)
            Some(ARTCC.AnyARTCC)
          } else None
        }
      case _ => None
    }
  }

  def handleSFDPSMessage: Receive = {
    case BusEvent(_,track:SfdpsTrack,_) => if (acceptSrc(track.src)) handleTrack(track)
    case BusEvent(_,tracks:SfdpsTracks,_) => if (acceptSrc(tracks.artccId)) tracks.foreach(handleTrack)
    case BusEvent(_,dropped:TrackDropped,_) => dropTrackEntry(dropped.cs)
    case BusEvent(_,completed:TrackCompleted,_) => dropTrackEntry(completed.cs)
  }

  override def handleMessage = handleSFDPSMessage orElse super.handleMessage

  def showArtccSymbol (artcc: ARTCC) = addRenderable(new ArtccSymbol(artcc,this))
  def showArtccSymbols = ARTCC.artccList.foreach(showArtccSymbol)

  def selectARTCCs(res: MultiSelection.Result[ARTCC]): Unit ={
    res match {
      case MultiSelection.SomeSelected(newSel) =>
        val lastSel = selARTCCs
        lastSel.diff(newSel).foreach(a => releaseTopic(Some(a))) // removed ARTCCs
        newSel.diff(lastSel).foreach(a => requestTopic(Some(a))) // added ARTCCs
        selARTCCs = newSel
        removeTrackEntries( e=> !isSelectedARTCC(e.obj.src))
      case MultiSelection.NoneSelected(_) =>
        if (selARTCCs.nonEmpty) selARTCCs.foreach(a => releaseTopic(Some(a)))
        selARTCCs = Seq.empty[ARTCC]
        clearTrackEntries
      case MultiSelection.AllSelected(_) =>
        if (!isAllSelected) selARTCCs.foreach(a => releaseTopic(Some(a)))
        requestTopic(Some(ARTCC.AnyARTCC))
        selARTCCs = Seq(ARTCC.AnyARTCC)
      case MultiSelection.Canceled(_) => // do nothing
    }

    redraw
  }

  def selectedARTCCs: Seq[ARTCC] = {
    if (isAllSelected) ARTCC.artccList else selARTCCs
  }

  def isSelectedARTCC(id: String): Boolean = {
    selARTCCs.size match {
      case 0 => false
      case 1 => selARTCCs(0).isMatching(id)
      case _ =>
        selARTCCs.foreach { a =>
          if (a.isMatching(id)) return true
        }
        false
    }
  }
  @inline def isAllSelected: Boolean = selARTCCs.nonEmpty && selARTCCs(0).matchesAny
  @inline def isNoneSelected: Boolean = selARTCCs.isEmpty || selARTCCs(0).matchesNone

  // this is a potential high frequency call in case we receive per-track updates so we have to optimize
  final def acceptSrc (src: String): Boolean = {
    if (selectedOnly) {
      selARTCCs.size match {
        case 0 => false
        case 1 => selARTCCs(0).isMatching(src) // avoid iterator
        case _ => selARTCCs.exists( _.isMatching(src))
      }
    } else true
  }

  // pcik support for ARTCC symbols
  override def selectNonTrack(obj: RaceLayerPickable, action: EventAction): Unit = {
    obj.layerItem match {
      case artcc: ARTCC =>
        action match {
          case EventAction.LeftClick =>
            if (selARTCCs.nonEmpty) selARTCCs.foreach(a => releaseTopic(Some(a)))
            selARTCCs = Seq(artcc)
            clearTrackEntries
            requestTopic(Some(artcc))
            selPanel.updateSelection(selARTCCs)

          //case EventAction.LeftDoubleClick =>
          case _ => // ignored
        }
      case _ => // ignore
    }
  }
}
