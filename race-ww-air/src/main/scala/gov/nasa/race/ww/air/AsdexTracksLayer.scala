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
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.{GreatCircle, LatLonPos}
import gov.nasa.race.ifSome
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.{Feet, Meters, UsMiles, meters2Feet}
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.track.TrackRenderLevel.{Dot, Label, Symbol}
import gov.nasa.race.ww.track.{TrackEntry, TrackLayer, TrackLayerInfoPanel, TrackSymbol}
import gov.nasa.race.ww.{EyePosListener, Images, RaceView}
import gov.nasa.worldwind.geom.Position

// we need a special symbol because we have different attributes for different track categories (unknown/vehicle/aircraft)
class AsdexTrackSymbol (trackEntry: TrackEntry[AsdexTrack]) extends TrackSymbol[AsdexTrack](trackEntry) {
  override def update (newTrack: AsdexTrack) = {
    super.update(newTrack)
    if (newTrack.isAircraft && attrs.getImage == null) updateAttributes
  }
  override def setSymbolAttrs = if (trackEntry.obj.isAircraft) super.setSymbolAttrs else setLabelAttrs
}

class AsdexTracksLayer (raceView: RaceView,config: Config)
                           extends TrackLayer[AsdexTrack](raceView,config) with AirLocator with EyePosListener {

  override protected def createLayerInfoPanel = new TrackLayerInfoPanel[AsdexTrack](raceView,this) {
    contents += new StaticSelectionPanel[Airport,IdAndNamePanel[Airport]]("select airport",Airport.NoAirport +: Airport.airportList, 40,
      new IdAndNamePanel[Airport]( _.id, _.city), selectAirport).styled()
  }.styled('consolePanel)

  override def defaultSymbolColor = Color.yellow
  override def defaultSymbolImage = Images.getPlaneImage(color)
  override def defaultLabelThreshold = Meters(12000.0).toMeters
  override def defaultSymbolThreshold = Meters(8000.0).toMeters

  override def getSymbol(e: TrackEntry[AsdexTrack]) = Some(new AsdexTrackSymbol(e))

  // these are used to turn on/off the data, relative to the airport
  val activeDistance = UsMiles(config.getDoubleOrElse("view-distance", 10d)) // in US miles
  val activeAltitude = Feet(config.getDoubleOrElse("view-altitude", 60000d)) // feet above ground
  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 20000d)) // feet above ground

  var selAirport: Option[Airport] = None // selected airport
  var active = false

  override def initializeLayer() = raceView.addEyePosListener(this)
  override def eyePosChanged(eyePos: Position, animationHint: String): Unit = checkAirportChange(eyePos)

  def selectAirport (a: Airport) = raceView.trackUserAction(gotoAirport(a))

  def lookupAirport (pos: LatLonPos, dist: Length): Option[Airport] = {
    Airport.asdexAirports.find( e=> GreatCircle.distance(pos, e._2.position) < dist).map(_._2)
  }

  def releaseCurrentAirport = {
    clearTrackEntries
    releaseAll
    selAirport = None
    active = false
  }

  def checkAirportChange (eyePos: Position): Unit = {
    val newAirport = lookupAirport(eyePos, activeDistance)
    val eyeAltitude = meters2Feet(eyePos.getAltitude)
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
          active = true
        }
      }
    }
  }

  def gotoAirport (airport: Airport) = {
    if (airport eq Airport.NoAirport) { // just unset
      if (selAirport.isDefined) releaseCurrentAirport
    } else {
      val alt = gotoAltitude.toMeters + airport.elevation.toMeters
      raceView.panTo(airport.position, alt)
    }
  }

  def handleAsdexTracksLayerMessage: Receive = {
    case BusEvent(_, updateMsg: AsdexTracks, _) =>
      ifSome(selAirport){ ap =>
        if (active && ap.id == updateMsg.airport) {
          incUpdateCount
          updateTracks(updateMsg.tracks)
          wwdRedrawManager.redraw()
        }
      }
  }

  def updateTracks(newTracks: Seq[AsdexTrack]) = {
    newTracks.foreach{ t=>
      getTrackEntry(t) match {
        case Some(te) =>
          if (t.drop) removeTrackEntry(te) else updateTrackEntry(te, t)

        case None =>
          if (!t.drop) addTrackEntry(t)
      }
    }
  }

  override def handleMessage = handleAsdexTracksLayerMessage orElse super.handleMessage
}
