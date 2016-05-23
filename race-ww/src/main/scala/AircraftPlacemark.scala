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

package gov.nasa.race.ww

import java.awt.image.BufferedImage
import gov.nasa.race.common._
import gov.nasa.race.common.DateTimeUtils._
import gov.nasa.race.data.IdentifiableInFlightAircraft
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.render.{Offset, PointPlacemarkAttributes, PointPlacemark}

import scala.reflect.ClassTag

object AircraftDetailLevel extends Enumeration {
  type AircraftDetailLevel = Value
  val SymbolLevel, LabelLevel, DotLevel = Value
}
import AircraftDetailLevel._

trait AircraftRenderContext[T <: IdentifiableInFlightAircraft] {
  def detailLevel: AircraftDetailLevel
  def lineColor: String
  def labelColor: String
  def image (t: T): BufferedImage
}

/**
  * Renderable entry representing aircraft
  */
class AircraftPlacemark[T <: IdentifiableInFlightAircraft](var t:T, val ctx: AircraftRenderContext[T])
                                                extends PointPlacemark(t) {
  var attrs = new PointPlacemarkAttributes

  setAltitudeMode(WorldWind.ABSOLUTE)
  updateDisplayName
  updateAttributes

  def updateDisplayName = {
    val sb = new StringBuilder ++=
      "flight " ++= t.cs += '\n' ++=
      hhmmssZ.print(t.date) += '\n' ++=
      "alt: " ++= t.altitude.toFeet.toInt.toString ++= "ft\n" ++=
      "hdg: " ++= normalizedDegrees(t.heading.toDegrees).toInt.toString ++= "°\n" ++=
      "spd: " ++= t.speed.toKnots.toInt.toString ++= "kn"
    setValue( AVKey.DISPLAY_NAME, sb.toString)
  }

  def update (newT: T) = {
    t = newT
    setPosition(newT)
    updateDisplayName
    updateAttributes
  }

  def updateAttributes = {
    attrs.setLabelColor(ctx.labelColor)
    attrs.setLineColor(ctx.lineColor)

    ctx.detailLevel match {
      case DotLevel =>
        setLabelText(null)
        attrs.setScale(5d)
        attrs.setImage(null)
        attrs.setUsePointAsDefaultImage(true)

      case LabelLevel =>
        setLabelText(t.cs)
        attrs.setScale(5d)
        attrs.setImage(null)
        attrs.setUsePointAsDefaultImage(true)

      case SymbolLevel =>
        setLabelText(t.cs)
        attrs.setImage(ctx.image(t))
        attrs.setScale(0.35)
        attrs.setImageOffset(Offset.CENTER)
        attrs.setHeading(t.heading.toDegrees)
        attrs.setHeadingReference(AVKey.RELATIVE_TO_GLOBE)
    }

    setAttributes(attrs)
  }
}
