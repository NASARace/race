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
package gov.nasa.race.common

import scala.collection.Map
import scala.collection.mutable.ArrayBuffer

/**
  * a threshold level that is defined by its upper boundary and an associated action on a generic item type.
  * This is normally used within a (ordered) ThresholdLevelList so that we can determine which
  * level a given value belongs to
  */
class ThresholdLevel[A] (val upperBoundary: Double)(val action: (A)=>Unit) {

  @inline final def < (other: ThresholdLevel[A]) = upperBoundary < other.upperBoundary
  @inline final def < (d: Double) = upperBoundary < d
  @inline final def <= (other: ThresholdLevel[A]) = upperBoundary <= other.upperBoundary
  @inline final def <= (d: Double) = upperBoundary <= d


  @inline final def > (other: ThresholdLevel[A]) = upperBoundary > other.upperBoundary
  @inline final def > (d: Double) = upperBoundary > d
  @inline final def >= (other: ThresholdLevel[A]) = upperBoundary >= other.upperBoundary
  @inline final def >= (d: Double) = upperBoundary >= d
}

/**
  * a sorted collection of ThresholdLevels that always contains one level for each provided value,
  * which is guaranteed by requiring a 'aboveAction' in the ctor that has a unlimited upper boundary
  *
  * Note that we only have one level for each upper boundary and replace the previous one in cast the
  * level boundaries are the same
  */
class ThresholdLevelList[A] (aboveAction: (A)=>Unit) {
  private val levels = ArrayBuffer(new ThresholdLevel[A](Double.MaxValue)(aboveAction)) // sorted in descending order
  private var currentLevel: ThresholdLevel[A] = null

  /**
    * we assume that threshold levels are normally provided in descending order
    */
  def sortIn(ts: ThresholdLevel[A]*): ThresholdLevelList.this.type = {
    ts.foreach { t=>
      var i = 0
      while (i < levels.size && levels(i) > t) i += 1
      if (i == levels.size) {
        levels += t
      } else {
        if (levels(i) == t) {
          levels.update(i, t)
        } else {
          levels.insert(i, t)
        }
      }
    }
    this
  }

  /**
    * note there is always a matching level since we automatically add an 'above' level during construction
    */
  final def getContainingLevel (d: Double): ThresholdLevel[A] = {
    var d0 = Double.MinValue
    var i = levels.size-1
    while (d0 <= d && levels(i) < d) {
      d0 = levels(i).upperBoundary
      i -= 1
    }
    levels(i)
  }

  def trigger(d: Double, a: A): ThresholdLevel[A] = {
    val level = getContainingLevel(d)
    level.action(a)
    currentLevel = level
    level
  }

  def triggerAll (d: Double, as: Iterable[A]): ThresholdLevel[A] = {
    val level = getContainingLevel(d)
    as.foreach(level.action)
    currentLevel = level
    level
  }

  // provided to avoid creating Iterables
  def triggerForEachValue (d: Double, map: Map[_,A]): ThresholdLevel[A] = {
    val level = getContainingLevel(d)
    map.foreach(e => level.action(e._2))
    currentLevel = level
    level
  }

  def setCurrentLevel (d: Double): ThresholdLevel[A] = {
    val level = getContainingLevel(d)
    currentLevel = level
    level
  }

  def triggerInCurrentLevel (a: A) = if (currentLevel != null) currentLevel.action(a)

  def triggerAllInCurrentLevel (as: Iterable[A]): Unit = {
    if (currentLevel != null) {
      as.foreach(currentLevel.action)
    }
  }

  def triggerForEachValueInCurrentLevel (map: Map[_,A]): Unit = {
    if (currentLevel != null) {
      map.foreach(e => currentLevel.action(e._2))
    }
  }
}