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

import java.awt.{Color, Insets}

import gov.nasa.race.common.DateTimeUtils._
import gov.nasa.race.data.{FlightInfo, FlightInfoUpdateRequest, InFlightAircraft}
import gov.nasa.race.swing.Filler
import gov.nasa.race.swing.GBPanel.Anchor
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{AncestorAdded, AncestorObservable, AncestorRemoved, FieldPanel, GBPanel}
import gov.nasa.race.ww.RaceView
import org.joda.time.DateTime

import scala.swing.event.ButtonClicked
import scala.swing.{Action, BoxPanel, Button, CheckBox, Orientation, Panel}


/**
  * RaceLayer item panel for FlightEntry objects
  */
class FlightEntryPanel[T <: InFlightAircraft] (raceView: RaceView, layer: FlightLayer[T]) extends BoxPanel(Orientation.Vertical) {

  class FlightEntryFields extends FieldPanel { // we need a named type or field access will use reflection
    val cs   = addField("cs:")
    val date = addField("date:")
    val pos  = addField("position:")
    val alt  = addField("altitude:")
    val hdg  = addField("heading:")
    val spd  = addField("speed:")
    addSeparator
    val dep  = addField("departure:", "…")
    val arr  = addField("arrival:", "…")
    val acType = addField("aircraft:", "…")
    setContents
  }
  val fields = new FlightEntryFields().styled()

  val pathCb = new CheckBox("path").styled()
  val infoCb = new CheckBox("info").styled()
  val markCb = new CheckBox("mark").styled()
  val centerCb = new CheckBox("center").styled()

  val buttonPanel = new GBPanel {
    val dismissBtn = new Button(Action("⏏"){
      raceView.trackUserAction {
        layer.releaseFlightInfoUpdates(flightEntry)
        layer.dismissEntryPanel(flightEntry)
        flightEntry = null
      }
    }).styled()

    val c = new Constraints(insets = new Insets(5, 0, 0, 0), anchor = Anchor.West)
    layout(pathCb)   = c(0,0)
    layout(infoCb)   = c(1,0)
    layout(markCb)   = c(2,0)
    layout(centerCb) = c(3,0)
    layout(new Filler().styled()) = c(4,0).weightx(0.5)
    layout(dismissBtn) = c(5,0).weightx(0)
  }.styled()

  contents += fields
  contents += buttonPanel

  listenTo(centerCb,pathCb,infoCb,markCb)
  reactions += {
    case ButtonClicked(`centerCb`) => raceView.trackUserAction { centerFlightEntry(centerCb.selected) }
    case ButtonClicked(`pathCb`)   => raceView.trackUserAction { setPath(pathCb.selected) }
    case ButtonClicked(`infoCb`)   => raceView.trackUserAction { setInfo(infoCb.selected) }
    case ButtonClicked(`markCb`)   => raceView.trackUserAction { setMark(markCb.selected) }
  }

  var flightEntry: FlightEntry[T] = _

  def isShowing(e: FlightEntry[T]) = showing && flightEntry == e

  def setFlightEntry (e: FlightEntry[T]) = {
    if (e != flightEntry) {
      if (flightEntry != null) layer.releaseFlightInfoUpdates(flightEntry)
      flightEntry = e
      changedFlightEntryOptions
      update
      layer.requestFlightInfoUpdates(flightEntry)
    }
  }

  def update = {
    val obj = flightEntry.obj
    fields.cs.text = obj.cs
    fields.date.text = hhmmss.print(obj.date)
    fields.pos.text = f"${obj.position.φ.toDegrees}%.6f° , ${obj.position.λ.toDegrees}%.6f°"
    fields.alt.text = f"${obj.altitude.toFeet.toInt}%d ft"
    fields.hdg.text = f"${obj.heading.toDegrees.toInt}%d°"
    fields.spd.text = f"${obj.speed.toKnots.toInt}%d kn"
  }

  def setFlightInfo (fInfo: FlightInfo) = {
    def optString(opt: Option[String]): String = if (opt.isDefined) opt.get else "…"
    def optDate(opt: Option[DateTime]): String = if (opt.isDefined) hhmmss.print(opt.get) else "…"

    if (fInfo.cs == flightEntry.obj.cs) {
      fields.dep.text = s"${optString(fInfo.departurePoint)}  ${optDate(fInfo.etd)} / ${optDate(fInfo.atd)}"
      fields.arr.text = s"${optString(fInfo.arrivalPoint)}  ${optDate(fInfo.eta)} / ${optDate(fInfo.ata)}"
      fields.acType.text = s"${optString(fInfo.acCat)} ${optString(fInfo.acType)}"
    }
  }

  def centerFlightEntry(centerIt: Boolean) = {
    if (centerIt) layer.centerFlightEntry(flightEntry)
    else layer.stopCenteringFlightEntry
  }

  def setPath(showIt: Boolean) = {
    flightEntry.setPath(showIt)
    layer.changedFlightEntryOptions(flightEntry, if (showIt) layer.ShowPath else layer.HidePath)
    layer.redraw
  }
  def setInfo(showIt: Boolean) = {
    flightEntry.setInfo(showIt)
    layer.changedFlightEntryOptions(flightEntry, if (showIt) layer.ShowInfo else layer.HideInfo)
    layer.redraw
  }
  def setMark(showIt: Boolean) = {
    flightEntry.setMark(showIt)
    layer.changedFlightEntryOptions(flightEntry, if (showIt) layer.ShowMark else layer.HideMark)
    layer.redraw
  }

  def changedFlightEntryOptions = {
    if (flightEntry != null) {
      centerCb.selected = flightEntry.isCentered
      pathCb.selected = flightEntry.hasPath
      infoCb.selected = flightEntry.hasInfo
      markCb.selected = flightEntry.hasMark
    }
  }
}
