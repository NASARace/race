/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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

object Rev {
  def apply (l2: Short, l1: Short) = new Rev(l2,l1)
  def apply (l3: Short, l2: Short, l1: Short) = new Rev(l3,l2,l1)
  def apply (l4: Short, l3: Short, l2: Short, l1: Short) = new Rev(l4,l3,l2,l1)

}

/**
  * a wrapper for generic revisions (coded, 4 levels with 16384 revs each)
  */
case class Rev(rev: Long) {
  def this (l2: Short, l1: Short) = this(l2.toLong << 16 | l1)
  def this (l3: Short, l2: Short, l1: Short) = this(l3.toLong << 32 | l2.toLong << 16 | l1)
  def this (l4: Short, l3: Short, l2: Short, l1: Short) = this(l4.toLong << 48 | l3.toLong << 32 | l2.toLong << 16 | l1)

  //--- level access
  @inline final def level1: Int = (rev & 0xffff).toInt
  @inline final def level2: Int = ((rev >> 16)& 0xffff).toInt
  @inline final def level3: Int = ((rev >> 32)& 0xffff).toInt
  @inline final def level4: Int = ((rev >> 48)& 0xffff).toInt

  def numberOfLevels = {
    if ((rev >> 48) != 0) 4
    else if ((rev >> 32) != 0) 3
    else if ((rev >> 16) != 0) 2
    else 1
  }

  //--- comparison
  @inline final def == (r: Rev) = rev == r.rev
  @inline final def > (r: Rev) = rev > r.rev
  @inline final def >= (r: Rev) = rev >= r.rev
  @inline final def < (r: Rev) = rev < r.rev
  @inline final def <= (r: Rev) = rev <= r.rev

  //--- formatting
  override def toString = {
    var showSubLevel = false
    val sb = new StringBuilder

    def appendHigherLevel (i: Int, r: Long): Unit = {
      if (showSubLevel || r > 0){
        sb.append(r)
        sb.append('.')
        showSubLevel = true
      }
    }
    appendHigherLevel(4,level4)
    appendHigherLevel(3,level3)
    appendHigherLevel(2,level2)
    sb.append(level1)
    sb.toString
  }
}
