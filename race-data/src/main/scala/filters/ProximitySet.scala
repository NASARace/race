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

import gov.nasa.race.data.{GreatCircle, Positionable}
import squants.Length

import scala.collection.mutable.ArrayBuffer

class Proximity [A<:Positionable] (var distance: Length, val obj: A) extends Ordered[Proximity[A]] {
  def compare (other: Proximity[A]): Int = {
    val d = distance.toMeters - other.distance.toMeters
    if (d == 0) 0
    else if (d < 0) 1
    else -1
  }

  override def toString = s"($distance,$obj)"
}

/**
  * a filter that keeps a sorted list of n<maxSize Positionables
  *
  * this assumes that both center and proximities can move, and we have a large number events with
  * potential proximities of which only a small number falls within the maxDistance boundary. We also assume
  * that proximities are repeatedly tested, which means we don't have to keep track of the ones that initially
  * fall outside the boundaries
  */
class ProximitySet [A <:Positionable](val center: Positionable, val maxDistance: Length, val maxSize: Int, val correlate: (A,A)=>Boolean) {

  // compute boundaries of pre-check square - if positionable is outside we don't have to calculate the greatcircle distance
  final val dDeg = maxDistance.toNauticalMiles / 60.0

  val proximities = new ArrayBuffer[Proximity[A]](maxSize)

  var cpos = center.position
  var cLatDeg = cpos.φ.toDegrees
  var cLonDeg = cpos.λ.toDegrees

  //--- delegators
  def contains (p: A) = proximities.contains(p)
  def size = proximities.size
  def isEmpty = proximities.isEmpty
  def nonEmpty = proximities.nonEmpty
  def iterator = proximities.iterator
  def foreach (f: (Proximity[A]) => Unit): Unit = proximities.foreach(f)

  //--- mutators
  def updatePositions: Unit = {
    cpos = center.position
    cLatDeg = cpos.φ.toDegrees
    cLonDeg = cpos.λ.toDegrees
    var changed = false

    proximities.foreach { p =>
      val newDist = GreatCircle.distance(cpos,p.obj.position)
      if (newDist != p.distance) {
        p.distance = newDist
        changed = true
      }
    }
    if (changed) {
      proximities.sorted
      var i = proximities.size-1
      while (i>=0 && proximities(i).distance > maxDistance) {
        proximities.remove(i)
        i -= 1
      }
    }
  }

  /**
    * sort in new proximity, if within maxDistance and maxSize is not yet reached
    * we assume neither cpos nor other proximities are changed while we add a new proximity
    *
    * not very functional, but has to execute in const space since it might be called frequently
    */
  def sortIn (p: A): Boolean = {
    def _filter (p: A): Unit = {
      for ((e,i) <- proximities.zipWithIndex) {
        if (correlate(e.obj,p)) {
          proximities.remove(i)
          return
        }
      }
    }
    def _sortIn (ep: Proximity[A]): Boolean = {
      for ((e,i) <- proximities.zipWithIndex) {
        if (e.compare(ep) < 0) {
          proximities.insert(i, ep)
          if (proximities.size > maxSize) proximities.remove(maxSize)
          return true
        }
      }
      false
    }
    def _append (ep: Proximity[A]): Boolean = {
      if (proximities.size < maxSize) {
        proximities.append(ep)
        true
      } else false // maxSize reached
    }

    val ppos = p.position
    // pre-filter, avoiding expensive computation
    if (Math.abs(cLonDeg - ppos.λ.toDegrees) > dDeg || Math.abs(cLatDeg - ppos.φ.toDegrees) > dDeg) return false

    val dist = GreatCircle.distance(cpos, ppos)
    if (dist > maxDistance) return false

    proximities.size match {
      case 0 => // this is the first proximity contact
        proximities.append(new Proximity[A](dist,p))
        true
      case 1 if correlate(p,proximities(0).obj) =>  // only distance changed
        proximities(0).distance = dist
        true
      case _ =>
        _filter(p) // remove object if already there (case assumed unlikely)
        val ep = new Proximity[A](dist, p)
        _sortIn(ep) || _append(ep)
    }
  }

  //--- debug
  def dump = {
    for ((e,i) <- proximities.zipWithIndex) {
      println(s"[$i]: $e")
    }
  }
}
