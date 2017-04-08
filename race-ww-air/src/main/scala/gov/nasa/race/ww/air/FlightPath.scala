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

import java.util.Vector

import gov.nasa.race.air.InFlightAircraft
import gov.nasa.race.common.BasicTimeSeries
import gov.nasa.race.geo.DatedAltitudePositionable
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.render.{BasicShapeAttributes, Material, Path}

object PathRenderLevel extends Enumeration {
  type PathRenderLevel = Value
  val None, Line, LinePos = Value
}

/**
  * WWJ Path to display aircraft flight paths
  */
class FlightPath[T <: InFlightAircraft](val flightEntry: FlightEntry[T]) extends Path with BasicTimeSeries {
  val layer = flightEntry.layer
  val flightPath = flightEntry.flightPath

  val material = new Material(layer.pathColor)
  val attrs = new BasicShapeAttributes
  attrs.setOutlineWidth(1)
  attrs.setOutlineMaterial(material)
  attrs.isDrawInterior
  attrs.setInteriorOpacity(1.0)
  attrs.setInteriorMaterial(material)
  attrs.setEnableAntialiasing(true)
  setShowPositionsScale(4.0)
  setAttributes(attrs)

  updateAttributes

  // we don't use setShowPositionsThreshold because it is based on eye distance to position, not altitude
  setPathType(AVKey.LINEAR)
  setAltitudeMode(WorldWind.ABSOLUTE)

  var posList = new Vector[Position](flightPath.capacity)
  flightPath foreach { (_,latDeg,lonDeg,altMeters,_) =>
    posList.add(Position.fromDegrees(latDeg,lonDeg,altMeters))
  }
  setPositions(posList)


  def setLineAttrs = setShowPositions(false)
  def setLinePosAttrs = setShowPositions(averageUpdateFrequency > 1) // no point showing points for high frequency updates
  def updateAttributes = if (layer.pathDetails == PathRenderLevel.LinePos) setLinePosAttrs else setLineAttrs

  def addFlightPosition (fpos: DatedAltitudePositionable) = {
    addSample
    posList.add(fpos)
    setPositions(posList)
  }

  override def computePositionCount = numPositions = posList.size
}
