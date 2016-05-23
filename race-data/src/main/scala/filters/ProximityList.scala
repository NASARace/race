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

package gov.nasa.race.data.filters

import gov.nasa.race.common.WeightedArray
import gov.nasa.race.data.{GreatCircle, LatLonPos, Positionable}
import squants.Length

/**
  * a set of (lat,lon) Positionables that are ordered in increasing distance
  * from a given (variable) center, not exceeding a given max distance
  *
  * the weight basis is meters
  */
abstract class ProximityList[T<:Positionable, E<:WeightedArray.Entry[T]]
               (var _center: LatLonPos, var _maxDistance: Length)  extends WeightedArray[T,E] {

  // cached for efficiency reasons
  private var latDeg: Double = center.φ.toDegrees
  private var lonDeg: Double = center.λ.toDegrees
  private var maxDDeg = maxDistance.toNauticalMiles / 60.0

  /**
    * set new center, update distances and re-sort array
    * @param newCenter new center position
    */
  def center_= (newCenter: LatLonPos): Unit = {
    val newLatDeg = newCenter.φ.toDegrees
    val newLonDeg = newCenter.λ.toDegrees

    if (latDeg != newLatDeg || lonDeg != newLonDeg) {
      _center = newCenter
      latDeg = newLatDeg
      lonDeg = newLonDeg

      updateWeights( e => GreatCircle.distance(_center,e.obj.position).toMeters)
      weightCut(_maxDistance.toMeters)
    }
  }
  def center = _center

  def maxDistance_= (newDist: Length): Unit = {
    _maxDistance = newDist
    maxDDeg = newDist.toNauticalMiles / 60.0
    weightCut(_maxDistance.toMeters)
  }
  def maxDistance = _maxDistance

  /**
    * this can be called frequently, so avoid temporary objects
    * @param obj Positionable to add
    * @return index at which obj was sorted in, or -1 if it didn't make the cut
    */
  def tryAdd (obj: T): Int = {
    val pos = obj.position

    // pre-filter to avoid expensive GreatCircle computation (should filter out most)
    if (Math.abs(lonDeg - pos.λ.toDegrees) > maxDDeg ||
        Math.abs(latDeg - pos.φ.toDegrees) > maxDDeg) return -1

    val dist = GreatCircle.distance(center, pos)
    if (dist > maxDistance) return -1

    tryAdd(obj,dist.toMeters)
  }
}
