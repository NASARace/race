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

import java.util.Vector

import gov.nasa.race.common.BasicTimeSeries
import gov.nasa.race.track.{TrackPoint3D, TrackedObject}
import gov.nasa.race.ww.Implicits._
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.render.{BasicShapeAttributes, Path}


/**
  * WWJ Path to display aircraft flight paths
  */
class TrackPath[T <: TrackedObject](val entry: TrackEntry[T]) extends Path with BasicTimeSeries {
  val flightPath = entry.trajectory

  val attrs = new BasicShapeAttributes
  attrs.setOutlineWidth(1)
  attrs.setOutlineMaterial(entry.lineMaterial)
  attrs.setEnableAntialiasing(true)
  attrs.setDrawInterior(false)

  // those we set in preparation if there is a request to draw contours
  attrs.setInteriorOpacity(0.3)
  attrs.setInteriorMaterial(entry.lineMaterial)

  setShowPositionsScale(4.0)
  setAttributes(attrs)

  // we don't use setShowPositionsThreshold because it is based on eye distance to position, not altitude
  setPathType(AVKey.LINEAR)
  setAltitudeMode(WorldWind.ABSOLUTE)

  var posList = new Vector[Position](flightPath.capacity)
  flightPath foreach { (_,latDeg,lonDeg,altMeters,_) =>
    posList.add(Position.fromDegrees(latDeg,lonDeg,altMeters))
  }
  setPositions(posList)

  def setLineAttrs = setShowPositions(false)
  def setLinePosAttrs = setShowPositions(averageUpdateFrequency < 2) // no point showing points for high frequency updates

  def addTrackPosition(tp: TrackPoint3D) = {
    addSample
    posList.add(tp)
    setPositions(posList)
  }

  def setContourAttrs (showContour: Boolean) = {
    attrs.setDrawInterior(showContour)
    setExtrude(showContour)
    setDrawVerticals(showContour)
  }

  override def computePositionCount = numPositions = posList.size
}
