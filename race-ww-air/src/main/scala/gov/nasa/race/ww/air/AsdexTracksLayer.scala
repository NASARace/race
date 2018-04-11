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

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.{AirLocator, Airport, AsdexTrack, AsdexTracks}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.{GreatCircle, LatLonPos}
import gov.nasa.race.ifSome
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.track.Trajectory
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.{Feet, Meters, UsMiles, meters2Feet}
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.track.{TrackEntry, TrackLayer, TrackLayerInfoPanel}
import gov.nasa.race.ww.{EyePosListener, Images, RaceView}
import gov.nasa.worldwind.geom.Position


// NOTE - 'obj' is a var and might change (don't mask in ctor args)
class AsdexTrackEntry (o: AsdexTrack, trajectory: Trajectory, layer: AsdexTracksLayer)
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

class AsdexTracksLayer (val raceView: RaceView, val config: Config)
                           extends TrackLayer[AsdexTrack] with AirLocator with EyePosListener {

  override protected def createLayerInfoPanel = new TrackLayerInfoPanel[AsdexTrack](raceView,this) {
    contents += new StaticSelectionPanel[Airport,IdAndNamePanel[Airport]]("select airport",Airport.NoAirport +: Airport.airportList, 40,
      new IdAndNamePanel[Airport]( _.id, _.city), selectAirport).styled()
  }.styled('consolePanel)

  override def createTrackEntry(track: AsdexTrack)= new AsdexTrackEntry(track,createTrajectory(track),this)

  override def defaultSymbolImg = Images.getPlaneImage(color)
  override def defaultLabelThreshold = Meters(12000.0)
  override def defaultIconThreshold = Meters(8000.0)

  // these are used to turn on/off the data, relative to the airport
  val activeDistance = UsMiles(config.getDoubleOrElse("view-distance", 10d)) // in US miles
  val activeAltitude = Feet(config.getDoubleOrElse("view-altitude", 60000d)) // feet above ground
  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 20000d)) // feet above ground

  var selAirport: Option[Airport] = None // selected airport
  var active = false

  override def initializeLayer: Unit = {
    super.initializeLayer
    raceView.addEyePosListener(this)
  }
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
          if (t.isDropped) removeTrackEntry(te) else updateTrackEntry(te, t)

        case None =>
          if (!t.isDropped) addTrackEntry(t)
      }
    }
  }

  override def handleMessage = handleAsdexTracksLayerMessage orElse super.handleMessage
}
