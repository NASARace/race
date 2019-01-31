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
package gov.nasa.race.common

/**
  * index management for a circular buffer of arbitrary types
  *
  * Note that we don't use a generic element type parameter since we might use the
  * indices for separate value arrays
  */
trait CircularBuffer {
  val capacity: Int

  protected var head: Int = -1
  protected var tail: Int = -1
  protected var _size: Int = 0

  def size: Int = _size

  // startOffset == 0 is the first stored value
  @inline final protected def storeIdx(startOffset: Int): Int = (tail + startOffset) % capacity

  // update head/tail/_size for adding a new track point to be added at index 'head'
  protected def incIndices: Int = {
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
    head
  }

  def clear: Unit = {
    head = -1
    tail = -1
    _size = 0
  }

  // TODO - these need optional func arguments for clean up

  def drop (n: Int): Unit = {
    if (_size <= n){
      clear
    } else {
      tail = (tail + n) % capacity
      _size -= n
    }
  }

  def dropRight (n: Int): Unit = {
    if (_size <= n){
      clear
    } else {
      head = if (head >= n) head - n else capacity - (n - head)
      _size -= n
    }
  }

  // TODO - not safe for concurrent modification

  class ForwardIterator[T] (f: (Int)=>T) extends Iterator[T] {
    var j = tail

    override def hasNext: Boolean = j != head

    override def next(): T = {
      val i = j
      j = (j + 1) % capacity
      f(i)
    }
  }

  class ReverseIterator[T] (f: (Int)=>T) extends Iterator[T] {
    var j = head

    override def hasNext: Boolean = j != tail

    override def next(): T = {
      val i = j
      j -= 1
      if (j < 0) j = capacity - 1
      f(i)
    }
  }
}
