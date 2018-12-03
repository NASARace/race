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
package gov.nasa.race.track

/**
  * a trace over dynamic TrackedObject state variables
  */
trait TrackHistoryTrace extends TrackHistory {

  // note that head,tail are logical indices (1..capacity)
  protected var head: Int = -1
  protected var tail: Int = -1
  protected var _size: Int = 0

  override def size: Int = _size

  protected def setTrackData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double, hdg: Double, spd: Double, vr: Double): Unit
  protected def processTrackData(i: Int, idx: Int, f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit

  override def addPre(t: Long, lat: Double, lon: Double, alt: Double, hdg: Double, spd: Double, vr: Double): TrackHistory = {
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

    setTrackData(head, t, lat,lon,alt,hdg,spd,vr)

    this.head = head
    this.tail = tail

    this
  }

  /**
    * iterate through track points in order of entry (FIFO)
    */
  override def foreachPre(f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit = {
    var j = tail
    var i = 0
    val n = _size
    while (i < n) {
      processTrackData(i,j,f)
      j = (j + 1) % capacity
      i += 1
    }
  }

  /**
    * iterate through track points in reverse order of entry (LIFO)
    */
  override def foreachPreReverse(f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit = {
    var j = head
    var i = _size-1
    while (i >= 0) {
      processTrackData(i,j,f)
      j = if (j > 0) j - 1 else capacity - 1
      i -= 1
    }
  }
}
