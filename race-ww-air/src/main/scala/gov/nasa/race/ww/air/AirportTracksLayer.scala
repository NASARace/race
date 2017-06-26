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

package gov.nasa.race.ww.air

import java.awt.Color

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.{Airport, AirportTracks, Track}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.geo.{GreatCircle, LatLonPos}
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.swing.Style._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.{DynamicRaceLayerInfo, EyePosListener, RaceView, SubscribingRaceLayer, _}
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.render.{Offset, PointPlacemark, PointPlacemarkAttributes}

import scala.collection.concurrent.TrieMap



/**
  * WorldWind layer to display ASDE-X airport track layers
  */
class AirportTracksLayer (raceView: RaceView,config: Config)
                                           extends SubscribingRaceLayer(raceView,config)
                                              with DynamicRaceLayerInfo with EyePosListener {

  class TrackEntry(var track: Track) extends PointPlacemark(track) {
    var isAircraft = track.isAircraft // apparently it changes, so we need to store
    val attrs = new PointPlacemarkAttributes
    var id = getId(track)

    setPosition(track)
    setLabelText(id)
    initAttrs(track)
    setAttributes(attrs)

    def update (t: Track) = {
      setPosition(t)
      if (isAircraft) { // we don't change back from an aircraft into a unknown type
        attrs.setHeading(t.heading.getOrElse(Angle0).toDegrees)
      } else {
        if (t.isAircraft) { // we just changed into an aircraft
          id = getId(t)
          isAircraft = true
          initAttrs(t)
          setLabelText(id)
        }
      }
      setAttributes(attrs) // indicates to WW that we have changed attr values
    }

    def getId (t: Track) = t.acId match {
      case Some("UNKN") => t.id
      case Some(cs) => cs
      case None => t.id
    }

    def initAttrs (t: Track) = {
      if (t.isAircraft /*&& track.heading.isDefined*/){
        attrs.setImage(planeImg)
        attrs.setScale(0.3)
        attrs.setImageOffset(Offset.CENTER)
        attrs.setHeadingReference(AVKey.RELATIVE_TO_GLOBE)
        attrs.setHeading(t.heading.getOrElse(Angle0).toDegrees)
        attrs.setLabelColor(toABGRString(acColor))
        attrs.setLineColor(toABGRString(acColor))
      } else {
        attrs.setScale(5d)
        attrs.setImage(null)
        attrs.setUsePointAsDefaultImage(true)
        attrs.setLabelColor(toABGRString(color))
        attrs.setLineColor(toABGRString(color))
      }
    }
  }

  //val panel = new AirportLayerPanel().styled()
  val panel = new DynamicLayerInfoPanel {
    contents += new StaticSelectionPanel[Airport,IdAndNamePanel[Airport]]("select airport",Airport.NoAirport +: Airport.airportList, 40,
      new IdAndNamePanel[Airport]( _.id, _.city), selectAirport).styled()
  }.styled('consolePanel)

  val activeDistance = UsMiles(config.getDoubleOrElse("view-distance", 6d)) // in US miles
  val activeAltitude = Feet(config.getDoubleOrElse("view-altitude", 30000d)) // feet above ground
  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 20000d)) // feet above ground

  // this is the WorldWind limit in MSL (we cut off data at activeAltitude above ground)
  if (getMaxActiveAltitude == Double.MaxValue) setMaxActiveAltitude(activeAltitude.toMeters + 5000d)

  val color = config.getColorOrElse("color", Color.green)
  val acColor = config.getColorOrElse("aircraft-color", Color.yellow)
  val planeImg = Images.getPlaneImage(acColor)

  var selAirport: Option[Airport] = None // selected airport
  var active = false
  val tracks = TrieMap[String,TrackEntry]()
  override def size = tracks.size

  override def initializeLayer() = raceView.addEyePosListener(this)
  override def eyePosChanged(eyePos: Position, animationHint: String): Unit = checkAirportChange(eyePos)

  def selectAirport (a: Airport) = raceView.trackUserAction(gotoAirport(a))

  override def handleMessage = {
    case BusEvent(_, at: AirportTracks, _) =>
      ifSome(selAirport){ ap =>
        if (active && ap.id == at.airport) {
          count = count + 1
          updateTracks(at.tracks)
          wwdRedrawManager.redraw()
        }
      }
    case other => warning(f"$name ignoring message $other%30.30s..")
  }

  def updateTracks(newTracks: Seq[Track]) = {
    for (t <- newTracks){
      tracks.get(t.id) match {
        case Some(te) =>
          if (t.drop) {
            removeRenderable(te)
            tracks -= t.id
          } else {
            te.update(t)
          }
        case None =>
          if (!t.drop) {
            val te = new TrackEntry(t)
            tracks += t.id -> te
            addRenderable(te)
          }
      }
    }
  }

  def reset(): Unit = {
    removeAllRenderables()
    tracks.clear()
    releaseAll
  }

  def lookupAirport (pos: LatLonPos, dist: Length): Option[Airport] = {
    Airport.asdexAirports.find( e=> GreatCircle.distance(pos, e._2.pos) < dist).map(_._2)
  }

  def releaseCurrentAirport = {
    reset
    selAirport = None
    active = false
  }

  def checkAirportChange (eyePos: Position): Unit = {
    val newAirport = lookupAirport(eyePos, activeDistance)
    val eyeAltitude = meters2Feet(eyePos.getAltitude)
    if (selAirport.isDefined && selAirport == newAirport){ // no airport change, but check altitude
      if (active){
        if (eyeAltitude > activeAltitude.toFeet + selAirport.get.elev.toFeet){
          releaseCurrentAirport
        }
      }
    } else { // possible airport change
      if (active) releaseCurrentAirport
      ifSome(newAirport) { a =>
        if (eyeAltitude <= activeAltitude.toFeet + a.elev.toFeet){
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
      val alt = gotoAltitude.toMeters + airport.elev.toMeters
      raceView.panTo(airport.pos, alt)
    }
  }
}