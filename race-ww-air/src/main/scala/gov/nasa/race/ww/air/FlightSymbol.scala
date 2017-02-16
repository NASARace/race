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

import java.awt.Point

import gov.nasa.race.air.InFlightAircraft
import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.ww._
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.render.{Offset, PointPlacemark, PointPlacemarkAttributes}

object FlightRenderLevel extends Enumeration {
  type FlightRenderLevel = Value
  val Symbol, Label, Dot = Value
}
import gov.nasa.race.ww.air.FlightRenderLevel._

/**
  * Renderable representing 2D aircraft symbol
  *
  * Note this is a pick-enabled object type and hence we need a link to the corresponding
  * FlightEntry (the FlightPos objects referenced from there change upon update and hence
  * cannot be used to do a FlightEntry lookup)
  */
class FlightSymbol[T <: InFlightAircraft](val flightEntry: FlightEntry[T])
                                                  extends PointPlacemark(flightEntry.obj) with RaceLayerPickable {
  // RaceLayerObject
  override def layer = flightEntry.layer
  override def layerItem = flightEntry

  var showDisplayName = false
  var attrs = new PointPlacemarkAttributes

  //--- those are invariant
  attrs.setLabelColor(layer.labelColor)
  attrs.setLineColor(layer.lineColor)

  setAltitudeMode(WorldWind.ABSOLUTE)
  //updateDisplayName
  updateAttributes

  def setDisplayName(showIt:Boolean) = {
    if (showIt) {
      showDisplayName = true
      updateDisplayName
    } else showDisplayName = false
  }

  def updateDisplayName = {
    val obj = flightEntry.obj
    val s = s"${obj.cs}\n${hhmmss.print(obj.date)}\n${obj.altitude.toFeet.toInt} ft\n${obj.heading.toDegrees.toInt}°\n${obj.speed.toKnots.toInt} kn"
    setValue( AVKey.DISPLAY_NAME, s.toString)
  }

  def update (newT: T) = {
    setPosition(newT)
    attrs.setHeading(newT.heading.toDegrees)

    if (showDisplayName) updateDisplayName
    //updateAttributes
  }

  def setDotAttrs = {
    setLabelText(null)
    attrs.setScale(5d)
    attrs.setImage(null)
    attrs.setUsePointAsDefaultImage(true)
    setAttributes(attrs)
  }

  def setLabelAttrs = {
    setLabelText(flightEntry.obj.cs)
    attrs.setScale(5d)
    attrs.setImage(null)
    attrs.setUsePointAsDefaultImage(true)
    setAttributes(attrs)
  }

  def setSymbolAttrs = {
    val obj = flightEntry.obj

    setLabelText(obj.cs)
    attrs.setImage(layer.image(obj))
    attrs.setScale(0.35)
    attrs.setImageOffset(Offset.CENTER)
    attrs.setHeading(obj.heading.toDegrees)
    attrs.setHeadingReference(AVKey.RELATIVE_TO_GLOBE)
    setAttributes(attrs)
  }

  def updateAttributes = {
    layer.flightDetails match {
      case Dot => setDotAttrs
      case Label => setLabelAttrs
      case Symbol => setSymbolAttrs
    }
  }

  val screenPt = if (screenPoint != null) new Point(screenPoint.x.toInt, screenPoint.y.toInt) else new Point(0,0)
  def getScreenPoint: Point = {
    if (screenPoint != null) screenPt.setLocation(screenPoint.x, screenPoint.y)
    screenPt
  }

  val screenOffset = if (screenPoint != null) new Offset(screenPoint.x,screenPoint.y,AVKey.PIXELS,AVKey.PIXELS) else new Offset(.0,.0,AVKey.PIXELS,AVKey.PIXELS)
  def getScreenOffset: Offset = {
    if (screenPoint != null){ screenOffset.setX(screenPoint.x); screenOffset.setY(screenPoint.y) }
    screenOffset
  }
  def screenPointX = screenPoint.x
  def screenPointY = screenPoint.y
}