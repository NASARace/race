/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import gov.nasa.race.trajectory.{TrajectoryPoint, TDP3, Trajectory}
import gov.nasa.race.ww.Implicits._
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.render.{BasicShapeAttributes, Material, Path}


/**
  * WWJ representation of a Trajectory
  */
class TrajectoryPath (trajectory: Trajectory, lineMaterial: Material) extends Path {

  val attrs = createAttributes(lineMaterial)

  setShowPositionsScale(4.0)
  setAttributes(attrs)

  setPathType(AVKey.LINEAR)
  setAltitudeMode(WorldWind.ABSOLUTE)

  var posList = new Vector[Position](trajectory.size)
  trajectory.foreach(new TDP3(0,0,0,0)) { posList.add(_) }
  setPositions(posList)

  def createAttributes (lineMaterial: Material): BasicShapeAttributes = {
    val attrs = new BasicShapeAttributes
    attrs.setOutlineWidth(1)
    attrs.setOutlineMaterial(lineMaterial)
    attrs.setEnableAntialiasing(true)
    attrs.setDrawInterior(false)

    // those we set in preparation if there is a request to draw contours
    attrs.setInteriorOpacity(0.3)
    attrs.setInteriorMaterial(lineMaterial)

    attrs
  }

  /**
    * for tilted views
    */
  def setContourAttrs (showContour: Boolean) = {
    attrs.setDrawInterior(showContour)
    setExtrude(showContour)
    setDrawVerticals(showContour)
  }

  override def computePositionCount = numPositions = posList.size
}
