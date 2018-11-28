/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.track


/**
  * a trajectory with a fixed size of slots, keeping the last N track points
  * implemented as a simple ring buffer in which elements are never removed, only added
  */
class Trace (val capacity: Int) extends Trajectory {

  protected val lats: Array[Double] = new Array[Double](capacity)
  protected val lons: Array[Double] = new Array[Double](capacity)
  protected val alts: Array[Double] = new Array[Double](capacity)
  protected val dates: Array[Long] = new Array[Long](capacity)

  protected var head: Int = -1
  protected var tail: Int = -1
  protected var _size: Int = 0

  override def size: Int = _size

  override def add (lat: Double, lon: Double, alt: Double, t: Long): Trajectory = {
    var head = this.head
    var tail = this.tail

    if (head < 0) { // first entry
      head = 0
      tail = 0
      _size = 1
    } else {
      head = (head + 1) % capacity
      if (head == tail) {
        tail = (tail + 1) % capacity
      } else {
        _size += 1
      }
    }

    lats(head) = lat
    lons(head) = lon
    alts(head) = alt
    dates(head) = t

    this.head = head
    this.tail = tail

    this
  }

  /**
    * iterate through track points in order of entry (FIFO)
    */
  override def foreach(f: (Int,Double,Double,Double,Long)=>Unit): Unit = {
    var j = tail
    var i = 0
    val n = _size
    while (i < n) {
      f(i, lats(j),lons(j),alts(j),dates(j))
      j = (j + 1) % capacity
      i += 1
    }
  }

  /**
    * iterate through track points in reverse order of entry (LIFO)
    */
  override def foreachReverse(f: (Int,Double,Double,Double,Long)=>Unit): Unit = {
    var j = head
    var i = _size-1
    while (i >= 0) {
      f(i, lats(j),lons(j),alts(j),dates(j))
      j = if (j > 0) j - 1 else capacity - 1
      i -= 1
    }
  }
}
