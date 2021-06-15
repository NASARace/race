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

import java.awt.Color

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{AirLocator, Airport, AsdexTrack, AsdexTracks}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
import gov.nasa.race.ifSome
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{AMOSelection, AMOSelectionPanel, IdAndNamePanel, StaticSelectionPanel, scaledSize}
import gov.nasa.race.trajectory.MutTrajectory
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.{Feet, Meters, UsMiles, meters2Feet}
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.track._
import gov.nasa.race.ww.{Images, RaceViewer, ViewListener, _}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render.{PointPlacemark, PointPlacemarkAttributes}

import scala.collection.Seq

class AirportSymbol (val airport: Airport, val layer: AsdexTracksLayer) extends PointPlacemark(wwPosition(airport.position)) with RaceLayerPickable {
  var showDisplayName = false
  var attrs = new PointPlacemarkAttributes

  //setValue( AVKey.DISPLAY_NAME, tracon.id)
  setLabelText(airport.id)
  setAltitudeMode(WorldWind.RELATIVE_TO_GROUND)
  attrs.setImage(null)
  attrs.setLabelFont(layer.labelFont)
  attrs.setLabelColor(layer.locationColor)
  attrs.setLineColor(layer.locationColor)
  attrs.setUsePointAsDefaultImage(true) // we should use a different default image
  attrs.setScale(scaledSize(7).toDouble)
  setAttributes(attrs)

  override def layerItem: AnyRef = airport
}

/**
  * we need a specialized TrackEntry since the object type (aircraft/vehicle) is not
  * known a priori (we usually get that from a update message later-on)
  *
  * NOTE - 'obj' is a var and might change (don't mask in ctor args)
  */
class AsdexTrackEntry (o: AsdexTrack, trajectory: MutTrajectory, layer: AsdexTracksLayer)
                                            extends TrackEntry[AsdexTrack](o,trajectory,layer) {
  var wasAircraft = o.guessAircraft // we remember if it was an aircraft

  override def setIconLevel = ifSome(symbol) { sym =>
    if (wasAircraft) sym.setIconAttrs else sym.setLabelAttrs
  }

  override def setNewObj (newObj: AsdexTrack): Unit = {
    if (!wasAircraft) {
      if (newObj.guessAircraft){
        wasAircraft = true
        if (symbol.isDefined) setSymbolLevelAttrs
      }
    }
    super.setNewObj(newObj)
  }
}

class AsdexTracksLayer (val raceViewer: RaceViewer, val config: Config)
                           extends TrackLayer[AsdexTrack]
                             with AirLocator with ViewListener {

  val showLocations = config.getBooleanOrElse("show-locations", true)
  val locationColor = toABGRString(config.getColorOrElse("location-color", Color.orange))

  override def createTrackEntry(track: AsdexTrack)= new AsdexTrackEntry(track,createTrajectory(track),this)

  override def defaultSymbolImg = Images.getPlaneImage(color)
  override def defaultLabelThreshold = Meters(12000.0)
  override def defaultIconThreshold = Meters(8000.0)

  // these are used to turn on/off the data, relative to the airport
  val activeDistance = UsMiles(config.getDoubleOrElse("view-distance", 10d)) // in US miles
  val activeAltitude = Feet(config.getDoubleOrElse("view-altitude", 60000d)) // feet above ground
  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 20000d)) // feet above ground

  // note that we don't move the view here - if the configured view does not show the airport
  // we waste CPU but it would be equally confusing to move the initial view, which might focus
  // on a particular point around the airport
  var selAirport: Option[Airport] = None
  def active = selAirport.isDefined

  val selectedOnly = config.getBooleanOrElse("selected-only", true)

  val selPanel = new AMOSelectionPanel[Airport](
    "airport:",
    "select airport",
    Airport.airportList,
    selAirport,
    _.id, _.name,
    Math.min(Airport.airportList.size,40)
  )(processResult).defaultStyled
  panel.contents.insert(1, selPanel)

  if (showLocations) showAirportSymbols

  def showAirportSymbol (a: Airport) = {
    addRenderable(new AirportSymbol(a,this))
  }

  def showAirportSymbols = Airport.airportList.foreach(showAirportSymbol)

  override def mapTopic (to: Option[AnyRef]): Option[AnyRef] = {
    to match {
      case Some(id: String) => selAirport = Airport.asdexAirports.get(id)
      case _ => // ignore
    }
    selPanel.updateSelection(selAirport)
    selAirport
  }

  def configuredAirport: Option[Airport] = {
    val topics = config.getOptionalStringList("request-topics")
    if (topics.nonEmpty) Airport.asdexAirports.get(topics.head) else None
  }

  override def initializeLayer: Unit = {
    super.initializeLayer
    raceViewer.addViewListener(this)
    selAirport.foreach(selectAirport)
  }
  override def viewChanged(targetView: ViewGoal): Unit = checkAirportChange(targetView)

  def processResult (res: AMOSelection.Result[Airport]): Unit = {
    res match {
      case AMOSelection.NoneSelected(_) => releaseCurrentAirport
      case AMOSelection.OneSelected(Some(a:Airport)) => selectAirport(a)
      case _ => // ignore
    }
  }

  def selectAirport (a: Airport) = gotoAirport(a)

  def lookupAirport (pos: GeoPosition, dist: Length): Option[Airport] = {
    Airport.asdexAirports.find( e=> GreatCircle.distance(pos, e._2.position) < dist).map(_._2)
  }

  def releaseCurrentAirport = {
    clearTrackEntries
    releaseAll
    selAirport = None
    selPanel.updateSelection(selAirport)
  }

  def checkAirportChange (targetView: ViewGoal): Unit = {
    val eyePos = targetView.pos
    val eyeAltitude = meters2Feet(targetView.zoom)
    val newAirport = lookupAirport(eyePos, activeDistance)

    if (selAirport.isDefined && selAirport == newAirport){ // no airport change, but check altitude
      if (active){
        if (eyeAltitude > activeAltitude.toFeet + selAirport.get.elevation.toFeet){
          releaseCurrentAirport
        }
      }
    } else { // possible airport change
      if (active) releaseCurrentAirport
      ifSome(newAirport) { a =>
        if (eyeAltitude <= activeAltitude.toFeet + a.elevation.toFeet){
          requestTopic(newAirport)
          selAirport = newAirport
          selPanel.updateSelection(selAirport)
        }
      }
    }
  }

  def gotoAirport (airport: Airport) = {
    if (airport eq Airport.NoAirport) { // just unset
      if (selAirport.isDefined) releaseCurrentAirport
    } else {
      val alt = gotoAltitude + airport.elevation
      raceViewer.panTo(wwPosition(airport.position), alt.toMeters)
    }
  }

  @inline def acceptSrc (src: String): Boolean = {
    !selectedOnly || (selAirport.isDefined && selAirport.get.id == src)
  }

  def handleAsdexTrack (t: AsdexTrack): Unit = {
    if (acceptSrc(t.src)){
      getTrackEntry(t) match {
        case Some(te) => if (t.isDropped) removeTrackEntry(te) else updateTrackEntry(te, t)
        case None => if (!t.isDropped) addTrackEntry(t)
      }
    }
  }

  def handleAsdexTracks (tracks: AsdexTracks): Unit = {
    if (acceptSrc(tracks.assoc)){
      incUpdateCount
      updateTracks(tracks)
      wwdRedrawManager.redraw()
    }
  }

  def handleAsdexTracksLayerMessage: Receive = {
    case BusEvent(_, tracks: AsdexTracks, _) => handleAsdexTracks(tracks)
    case BusEvent(_, track: AsdexTrack, _) => handleAsdexTrack(track)
  }

  def updateTracks(newTracks: Seq[AsdexTrack]) = {
    newTracks.foreach{ t=>
      getTrackEntry(t) match {
        case Some(te) =>
          if (t.isDropped) removeTrackEntry(te) else updateTrackEntry(te, t)

        case None =>
          if (!t.isDropped) addTrackEntry(t)
      }
    }
  }

  override def handleMessage = handleAsdexTracksLayerMessage orElse super.handleMessage

  override def selectNonTrack(obj: RaceLayerPickable, action: EventAction): Unit = {
    obj.layerItem match {
      case a: Airport =>
        action match {
          case EventAction.LeftClick =>
            selectAirport(a)
          case _ => // ignore
        }
      case _ => // ignore
    }
  }
}
