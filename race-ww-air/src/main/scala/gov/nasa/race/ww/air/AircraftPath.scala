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
class AircraftPath[T <: InFlightAircraft] (val flightEntry: FlightEntry[T]) extends Path {
  val layer = flightEntry.layer
  val flightPath = flightEntry.flightPath

  var tLast: Long = System.currentTimeMillis
  var isHighUpdateRate = false

  val attrs = new BasicShapeAttributes
  attrs.setOutlineWidth(1)
  attrs.setOutlineMaterial(new Material(layer.pathColor))
  attrs.setEnableAntialiasing(true)
  setShowPositionsScale(6.0)
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
  def setLinePosAttrs = setShowPositions(!isHighUpdateRate)
  def updateAttributes = if (layer.pathDetails == PathRenderLevel.LinePos) setLinePosAttrs else setLineAttrs

  def addFlightPosition (fpos: DatedAltitudePositionable) = {
    val t = System.currentTimeMillis
    isHighUpdateRate = (t - tLast) < 1000 // update rate > 1Hz
    tLast = t

    posList.add(fpos)
    setPositions(posList)
  }

  override def computePositionCount = numPositions = posList.size
}
