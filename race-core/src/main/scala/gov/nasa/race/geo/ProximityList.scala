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

package gov.nasa.race.geo

import gov.nasa.race.common.WeightedArray
import gov.nasa.race.uom._


/**
  * a set of (lat,lon) Positionables that are ordered in increasing distance
  * from a given (variable) center, not exceeding a given max distance
  *
  * the weight basis is meters
  */
abstract class ProximityList[T<:GeoPositioned, E<:WeightedArray.Entry[T]]
               (var _center: GeoPosition,
                var _maxDistance: Length,
                val ignoreDistance: Length)  extends WeightedArray[T,E] {

  // cached for efficiency reasons
  private var latDeg: Double = center.φ.toDegrees
  private var lonDeg: Double = center.λ.toDegrees
  private var inLimitDeg = (maxDistance.toNauticalMiles + 0.5) / 60.0  // consider adding if within
  private val outLimitDeg = (maxDistance.toNauticalMiles + ignoreDistance.toNauticalMiles) / 60.0  // consider remove check if within

  /**
    * set new center, update distances and re-sort array
    * @param newCenter new center position
    */
  def center_= (newCenter: GeoPosition): Unit = {
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
    inLimitDeg = newDist.toNauticalMiles / 60.0
    weightCut(_maxDistance.toMeters)
  }
  def maxDistance = _maxDistance

  /**
    * add/resort object within maxDistance, and remove if going out of range
    *
    * this can be called frequently, avoid temporary objects
    * @param obj Positionable to add
    * @return index at which obj was sorted in, or -1 if it didn't make the cut
    */
  def updateWith (obj: T): Int = {
    val pos = obj.position
    val dlon = Math.abs(lonDeg - pos.λ.toDegrees)
    val dlat = Math.abs(latDeg - pos.φ.toDegrees)

    // pre-filter to avoid expensive GreatCircle computation (should filter out most)
    if (dlon < inLimitDeg &&  dlat < inLimitDeg) {
      val dist = GreatCircle.distance(center, pos)
      if (dist < maxDistance){
        return tryAdd(obj,dist.toMeters)
      }
    }

    // if > outLimitDeg we skip iterating over the list again because we assume the obj wasn't in there to begin with
    if (dlon < outLimitDeg && dlat < outLimitDeg) remove(obj)
    -1
  }
}
