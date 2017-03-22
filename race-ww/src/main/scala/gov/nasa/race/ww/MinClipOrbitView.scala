/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import java.lang.Math._
import gov.nasa.race.common._
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.view.orbit.BasicOrbitView

/**
  * a BasicOrbitView that tries to minimize clip distance
  * The approach is to compute the lowest far clip distance from the eye altitude, and then use the OpenGL depth
  * buffer resolution to get the closest near clip distance that is numerically stable
  *
  * In general, we get less artifacts (flicker actually) with larger near clip distance, hence we also maintain
  * an assumed maximum flight altitude and use the difference between it and the eye altitude if the latter exceeds
  * the former. This means the max flight altitude has to be set if we want to display satellites
  *
  * Note that we just default to standard OrbitView behavior if the view is tilted, since in this case we would
  * have to show flights that are beyond the earth horizon
  */
class MinClipOrbitView extends BasicOrbitView {
  protected final val RE: Double = 6.371e6 // mean earth radius in m
  protected final val REsquared = RE**2
  protected final val MaxSeaDepth: Double = 1.2e5 // we don't want to clip out the Mariana trench
  protected final val DefaultMaxFlightAltitude = 4.0e5  // highest altitude in meters we display
  protected lazy val MaxDepthBufferDist = computeMaxDepthBufferDist  // has to be lazy since we need a dc
  protected final val MinNearClipDistance = 10.0 // MINIMUM_NEAR_DISTANCE causes flicker and artifacts (numeric?)

  protected var α: Double = computeFov2 // FOV/2 in radians
  protected var cos_α: Double = cos(α)
  protected var hmax: Double = computeBoundaryElevation // eye altitude in [m] until which horizon is not visible in center view
  protected var maxFlightAltitude: Double = DefaultMaxFlightAltitude // max flight altitude in [m]

  def computeFov2: Double = {
    // TODO - compute from perspective matrix. 'fov' is set to 45deg, which seems off (measured is ~60deg)
    val hmax = 6.8e6  // empirical - eye elevation for ortho view until which horizon is not visible
    asin( RE / (RE + hmax))
  }

  def computeBoundaryElevation: Double = RE/sin(α) - RE  // spherical approximation

  def computeMaxDepthBufferDist: Double = (1L << dc.getGLRuntimeCapabilities.getDepthBits) -1

  /**
    *
    * @param newAltitude maximum displayed flight altitude in meters
    * @return true if new altitude was set and scene needs to be redrawn
    */
  def ensureMaxFlightAltitude (newAltitude: Double): Boolean = {
    if (newAltitude > maxFlightAltitude) {
      maxFlightAltitude = newAltitude
      true
    } else false
  }
  def resetMaxFlightAltitude (newAltitude: Double) = maxFlightAltitude = newAltitude

  override def computeNearDistance(eyePos: Position): Double = {
    val eyeElev: Double = eyePos.getElevation  // in m, above RE

    if (farClipDistance.toLong < MaxDepthBufferDist) MinNearClipDistance else super.computeNearDistance(eyePos)
  }

  // this gets called first
  override def computeFarDistance(eyePos: Position): Double = {
    val eyeElev: Double = eyePos.getElevation  // in m, above RE

    if (eyeElev > hmax  /* || getPitch != ZeroWWAngle */) {
      super.computeFarDistance(eyePos)
    } else {
      val h = RE + eyeElev
      val h_cos_α = h * cos_α
      val d = h_cos_α - sqrt(h_cos_α**2 - (h**2 - REsquared))
      d * cos_α + MaxSeaDepth  // we could further reduce this over land
    }
  }
}
