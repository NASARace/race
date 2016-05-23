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

package gov.nasa.race.ww.layers

import java.awt.Color
import javax.swing.SpringLayout.Constraints

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.data.Airport
import gov.nasa.race.data.{AirportTracks, Track, _}
import gov.nasa.race.swing._
import gov.nasa.race.swing.GBPanel
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww._
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.{Angle, Position}
import gov.nasa.worldwind.render.{Offset, PointPlacemark, PointPlacemarkAttributes}
import gov.nasa.worldwind.view.orbit.OrbitViewInputHandler
import squants.space.{Length, UsMiles}

import scala.collection.concurrent.TrieMap
import scala.swing.event.SelectionChanged
import scala.swing._

object AirportTracksLayer {

  def lookupAirport (pos: LatLonPos, dist: Length): Option[Airport] = {
    for ((id,ap) <- Airport.asdexAirports) {
      if (GreatCircle.distance(pos, ap.pos) < dist) return Some(ap)
    }
    None
  }
}

class AirportListRenderer extends GBPanel {
  val idLabel = new Label()
  idLabel.horizontalTextPosition = Alignment.Left
  val nameLabel = new Label()

  val c = new Constraints(fill=Fill.Horizontal, anchor=Anchor.West, ipadx=10)
  layout(idLabel) = c(0,0)
  layout(nameLabel) = c(1,0).weightx(0.5)

  def setAirport (airport: Airport) = {
    idLabel.text = airport.id
    nameLabel.text = airport.city
  }
}

/**
  * WorldWind layer to display ASDE-X airport track layers
  */
class AirportTracksLayer (raceView: RaceView,config: Config)
                                           extends SubscribingRaceLayer(raceView,config)
                                              with DynamicRaceLayerInfo with EyePosListener {

  class AirportLayerPanel extends DynamicLayerInfoPanel {
    val airportCombo = new ComboBox(Airport.airportList) {
      maximumRowCount = 20
      renderer = new ListView.AbstractRenderer[Airport,AirportListRenderer](new AirportListRenderer) {
        override def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, a: Airport, index: Int): Unit = {
          component.setAirport(a)
        }
      }
    }
    val airportSelectorPanel = new GBPanel {
      val c = new Constraints( fill=Fill.Horizontal, anchor=Anchor.West, insets=(8,2,0,2))
      layout(new Label("goto airport:").styled('labelFor)) = c(0,0).weightx(0.5)
      layout(airportCombo) = c(1,0).weightx(0)
    } styled()

    setContents // the standard fields
    contents += airportSelectorPanel

    listenTo(airportCombo.selection)
    reactions += {
      case SelectionChanged(`airportCombo`) => gotoAirport(airportCombo.selection.item)
    }
  }

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

  val panel = new AirportLayerPanel().styled()
  val activeDistance = UsMiles(config.getDoubleOrElse("view-distance", 5d))
  val activeAltitude = config.getDoubleOrElse("view-altitude", 8000d)
  val gotoAltitude = config.getDoubleOrElse("goto-altitude", 7000d)
  if (getMaxActiveAltitude == Double.MaxValue) setMaxActiveAltitude(activeAltitude)

  val color = config.getColorOrElse("color", Color.green)
  val acColor = config.getColorOrElse("aircraft-color", Color.yellow)
  val planeImg = Images.getPlaneImage(acColor)

  var airport: Option[Airport] = None
  var active = false
  val tracks = TrieMap[String,TrackEntry]()
  override def size = tracks.size

  override def initializeLayer() = raceView.addEyePosListener(this)
  override def eyePosChanged(eyePos: Position, animationHint: String): Unit = checkAirportChange(eyePos)

  override def handleMessage = {
    case BusEvent(_, at: AirportTracks, _) =>
      ifSome(airport){ ap =>
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
          } else te.update(t)
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

  def checkAirportChange (eyePos: Position): Unit = {
    val newAirport = AirportTracksLayer.lookupAirport(eyePos, activeDistance)
    if (newAirport != airport){
      if (airport.isDefined) reset // stop listening on the last airport
      if (newAirport.isDefined) {
        if (eyePos.getAltitude <= activeAltitude) {
          requestTopic(newAirport)
          active = true
        }
      } else {
        active = false
      }
      airport = newAirport

    } else { // no airport change, but check eye altitude
      if (eyePos.getAltitude > activeAltitude) {
        if (active) { // deactivate
          reset
          active = false
        }
      } else {
        if (!active) { // activate
          requestTopic(airport)
          active = true
        }
      }
    }
  }

  def gotoAirport (airport: Airport) = {
    raceView.setLastUserInput
    raceView.panTo(airport.pos, gotoAltitude)
  }
}