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

import gov.nasa.race._
import gov.nasa.race.swing.FieldPanel
import gov.nasa.race.track.{PlannedTrack, TrackInfo, Tracked3dObject}
import gov.nasa.race.ww.InteractiveLayerObjectPanel
import gov.nasa.race.uom.DateTime


class TrackFields[T <: Tracked3dObject] extends FieldPanel {
  val cs   = addField("cs:")
  val date = addField("date:")
  val pos  = addField("position:")
  val alt  = addField("altitude:")
  val hdg  = addField("heading:")
  val spd  = addField("speed:")
  val vr   = addField("vr:")
  addSeparator
  val dep  = addField("departure:", "…")
  val arr  = addField("arrival:", "…")
  val acType = addField("aircraft:", "…")
  setContents

  // owner has to make sure updates are for the right object

  def optString(opt: Option[String]): String = if (opt.isDefined) opt.get else "…"
  def optDate(opt: DateTime): String = if (opt.isDefined) opt.format_Hms else "…"

  def update (obj: T): Unit = {
    cs.text = obj.cs
    date.text = obj.date.format_Hms
    val opos = obj.position
    pos.text = f"${opos.latDeg}%.6f° , ${opos.lonDeg}%.6f°"
    alt.text = f"${opos.altFeet}%d ft"
    hdg.text = f"${obj.heading.toDegrees.toInt}%d°"
    spd.text = f"${obj.speed.toKnots.toInt}%d kn"
    vr.text = f"${obj.vr.toFeetPerSecond.toInt}%d fps"

    obj match {
      case po: PlannedTrack =>
        dep.text = s"${po.departurePoint}: ${optDate(po.departureDate)}"
        arr.text = s"${po.arrivalPoint}: ${optDate(po.arrivalDate)}"
      case _ => // ignore
    }
  }

  // TODO - trackinfo will be reworked
  def setTrackInfo (ti: TrackInfo): Unit = {
    dep.text = s"${optString(ti.departurePoint)}:  ${optDate(ti.etd)} / ${optDate(ti.atd)}"
    arr.text = s"${optString(ti.arrivalPoint)}:  ${optDate(ti.eta)} / ${optDate(ti.ata)}"
    acType.text = s"${optString(ti.trackCategory)} ${optString(ti.trackType)}"
  }
}


/**
  * RaceLayer item panel for FlightEntry objects
  */
class TrackEntryPanel[T <: Tracked3dObject](override val layer: TrackLayer[T])
                     extends InteractiveLayerObjectPanel[TrackEntry[T],TrackFields[T]](layer) {

  override def createFieldPanel = new TrackFields[T]

  def setTrackEntry(newEntry: TrackEntry[T]) = {
    ifSome (entry) { layer.releaseTrackInfoUpdates(_) }

    entry = Some(newEntry)
    updateTrackEntryAttributes
    update
    layer.requestTrackInfoUpdates(newEntry)
  }

  def update: Unit = ifSome(entry){ e=> fields.update(e.obj) }

  def setTrackInfo(ti: TrackInfo): Unit = ifSome(entry) { e=> fields.setTrackInfo(ti) }

  def setFocused(focusIt: Boolean): Unit = ifSome(entry) { layer.setFocused(_,focusIt) }

  def setPathContour (showIt: Boolean): Unit = {
    ifSome(entry) { layer.setPathContour(_,showIt) }
  }


  // this is just a notification that attributes have changed externally
  def updateTrackEntryAttributes: Unit = ifSome(entry) { e=>
    focusCb.selected = e.isFocused
    pathCb.selected = e.hasPath
    infoCb.selected = e.hasInfo
    markCb.selected = e.hasMark
  }
}
