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

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.ww.Implicits._
import gov.nasa.worldwind.awt.WorldWindowGLCanvas
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.globes.{Earth, Globe2D}

object InViewChecker {
  def apply(wwd: WorldWindowGLCanvas): InViewChecker = {
    wwd.getModel.getGlobe match {
      case globe2D: Globe2D => new Globe2DInViewChecker(wwd,globe2D)
      case earth: Earth => new EarthInViewChecker(wwd,earth)
      case other => new AlwaysInViewChecker(wwd)
    }
  }
}

trait InViewChecker {
  val wwd: WorldWindowGLCanvas
  def isInView (pos: Position): Boolean
  def isInView (pos: GeoPosition): Boolean = isInView(geoPosition2Position(pos))
}

class AlwaysInViewChecker (val wwd: WorldWindowGLCanvas) extends InViewChecker {
  override def isInView (pos: Position) = true
  override def isInView (pos: GeoPosition) = true
}

class Globe2DInViewChecker (val wwd: WorldWindowGLCanvas, val globe: Globe2D) extends InViewChecker {
  val viewLimits = globe.getProjection.getProjectionLimits

  override def isInView (pos: Position) = viewLimits.contains(pos)
}

class EarthInViewChecker (val wwd: WorldWindowGLCanvas, val earth: Earth) extends InViewChecker {
  val view = wwd.getView
  val eyePoint = view.getEyePoint
  val horizonDistance = view.getHorizonDistance
  val screenWidth = wwd.getWidth
  val screenHeight = wwd.getHeight

  override def isInView (pos: Position) = {
    val placePoint = earth.computePointFromPosition(pos.getLatitude, pos.getLongitude, pos.getElevation)
    val screenPoint = view.project(placePoint)
    val x = screenPoint.x
    val y = screenPoint.y

    if (x < 0 || y < 0 || x > screenWidth || y > screenWidth){
      false
    } else { // we still have to check for horizon distance (if the eyePos is high enough)
      placePoint.distanceTo3(eyePoint) < horizonDistance
    }
  }
}