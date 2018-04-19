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

import java.awt.Insets

import gov.nasa.race.swing.GBPanel.Anchor
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{FieldPanel, Filler, GBPanel}
import gov.nasa.race.track.{TrackInfo, TrackedObject}
import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.ww.{Images, RacePanel, RaceView}
import org.joda.time.DateTime

import scala.swing.event.ButtonClicked
import scala.swing.{Action, BoxPanel, Button, CheckBox, Orientation}

object TrackEntryPanel {
  val ejectIcon = Images.getIcon("eject-blue-16x16.png")
}

trait TrackEntryFields[T <: TrackedObject] extends FieldPanel { // we need a named type or field access will use reflection
  def update (obj: T): Unit
  def setTrackInfo (ti: TrackInfo): Unit = {} // not all TrackedObjects have them
}

/**
  * RaceLayer item panel for FlightEntry objects
  */
class TrackEntryPanel[T <: TrackedObject](raceView: RaceView, layer: TrackLayer[T])
                extends BoxPanel(Orientation.Vertical) with RacePanel {

  class TrackFields extends TrackEntryFields[T] {
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

    override def update (obj: T): Unit = {
      cs.text = obj.cs
      date.text = hhmmss.print(obj.date)
      pos.text = f"${obj.position.φ.toDegrees}%.6f° , ${obj.position.λ.toDegrees}%.6f°"
      alt.text = f"${obj.altitude.toFeet.toInt}%d ft"
      hdg.text = f"${obj.heading.toDegrees.toInt}%d°"
      spd.text = f"${obj.speed.toKnots.toInt}%d kn"
    }

    override def setTrackInfo (ti: TrackInfo): Unit = {
      def optString(opt: Option[String]): String = if (opt.isDefined) opt.get else "…"
      def optDate(opt: Option[DateTime]): String = if (opt.isDefined) hhmmss.print(opt.get) else "…"

      if (ti.cs == trackEntry.obj.cs) {
        dep.text = s"${optString(ti.departurePoint)}  ${optDate(ti.etd)} / ${optDate(ti.atd)}"
        arr.text = s"${optString(ti.arrivalPoint)}  ${optDate(ti.eta)} / ${optDate(ti.ata)}"
        acType.text = s"${optString(ti.trackCategory)} ${optString(ti.trackType)}"
      }
    }
  }

  val fields = createFieldPanel.styled()

  val pathCb = new CheckBox("path").styled()
  val path3dCb = new CheckBox("3d").styled()
  val infoCb = new CheckBox("info").styled()
  val markCb = new CheckBox("mark").styled()
  val centerCb = new CheckBox("center").styled()

  val buttonPanel = new GBPanel {
    val dismissBtn = new Button() {
      action = Action("") {
        raceView.trackUserAction {
          layer.releaseTrackInfoUpdates(trackEntry)
          layer.dismissEntryPanel(trackEntry)
          trackEntry = null
        }
      }
      icon = TrackEntryPanel.ejectIcon
    }.styled()

    val c = new Constraints(insets = new Insets(5, 0, 0, 0), anchor = Anchor.West)
    layout(pathCb)   = c(0,0)
    layout(path3dCb) = c(1,0)
    layout(infoCb)   = c(2,0)
    layout(markCb)   = c(3,0)
    layout(centerCb) = c(4,0)
    layout(new Filler().styled()) = c(5,0).weightx(0.5)
    layout(dismissBtn) = c(6,0).weightx(0)
  }.styled()

  contents += fields
  contents += buttonPanel

  listenTo(centerCb,pathCb,infoCb,markCb)
  reactions += {
    case ButtonClicked(`centerCb`) => raceView.trackUserAction { centerFlightEntry(centerCb.selected) }
    case ButtonClicked(`pathCb`)   => raceView.trackUserAction { setPath(pathCb.selected) }
    case ButtonClicked(`path3dCb`) => // show path vertices
    case ButtonClicked(`infoCb`)   => raceView.trackUserAction { setInfo(infoCb.selected) }
    case ButtonClicked(`markCb`)   => raceView.trackUserAction { setMark(markCb.selected) }
  }

  var trackEntry: TrackEntry[T] = _

  protected def createFieldPanel: TrackEntryFields[T] = new TrackFields

  def isShowing(e: TrackEntry[T]) = showing && trackEntry == e

  def setTrackEntry(e: TrackEntry[T]) = {
    if (e != trackEntry) {
      if (trackEntry != null) layer.releaseTrackInfoUpdates(trackEntry)
      trackEntry = e
      changedTrackEntryOptions
      update
      layer.requestTrackInfoUpdates(trackEntry)
    }
  }

  def update = fields.update(trackEntry.obj)

  def setTrackInfo(ti: TrackInfo) = fields.setTrackInfo(ti)

  def centerFlightEntry(centerIt: Boolean) = {
    if (centerIt) layer.startCenteringTrackEntry(trackEntry)
    else layer.stopCenteringTrackEntry
  }

  def setPath(showIt: Boolean) = {
    trackEntry.setPath(showIt)
    layer.changedTrackEntryOptions(trackEntry, if (showIt) layer.ShowPath else layer.HidePath)
    layer.redraw
  }
  def setInfo(showIt: Boolean) = {
    trackEntry.setInfo(showIt)
    layer.changedTrackEntryOptions(trackEntry, if (showIt) layer.ShowInfo else layer.HideInfo)
    layer.redraw
  }
  def setMark(showIt: Boolean) = {
    trackEntry.setMark(showIt)
    layer.changedTrackEntryOptions(trackEntry, if (showIt) layer.ShowMark else layer.HideMark)
    layer.redraw
  }

  def changedTrackEntryOptions = {
    if (trackEntry != null) {
      centerCb.selected = trackEntry.isCentered
      pathCb.selected = trackEntry.hasPath
      infoCb.selected = trackEntry.hasInfo
      markCb.selected = trackEntry.hasMark
    }
  }
}
