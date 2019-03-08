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
trait CircularSeq {

  // to be provided by concrete type
  val capacity: Int
  protected val cleanUp: Option[(Int)=>Unit] // element clean up function (e.g. to avoid memory leaks)

  // these are strictly monotone logical indices. Requires modulo op for storage index
  protected var head: Int = -1
  protected var tail: Int = -1

  protected var _size: Int = 0

  def size: Int = _size

  // startOffset == 0 is the first stored value
  @inline final protected def circularOffsetIdx(offset: Int): Int = (tail + offset) % capacity

  @inline final protected def circularIdx(i: Int): Int = i % capacity

  protected def setIndices (newHead: Int, newTail: Int): Unit = {
    if (newHead >= capacity) throw new IndexOutOfBoundsException(s"$newHead")
    if (newTail >= capacity) throw new IndexOutOfBoundsException(s"$newTail")

    head = newHead
    tail = newTail
    if (newHead >= newTail) {
      _size = newHead - newTail
    } else {
      _size = capacity - newTail + newHead
    }
  }

  /**
    * update head/tail/_size, return physical index at which next value will be added
    */
  protected def incIndices: Int = {
    if (head < 0) { // first entry
      head = 0
      tail = 0
      _size = 1
      0

    } else {
      head += 1
      val hi = head % capacity
      if (hi == (tail % capacity)) {
        tail += 1
      } else {
        _size += 1
      }
      hi
    }
  }

  def clear: Unit = {
    if (_size > 0) {
      cleanUp match {
        case Some(f) => for (i <- tail to head) f(i)
        case None =>
      }

      head = -1
      tail = -1
      _size = 0
    }
  }

  /**
    * move tail forward
    */
  def drop (n: Int): Unit = {
    if (_size <= n){
      clear
    } else {
      val nt = tail + n
      cleanUp match {
        case Some(f) => for (i <- tail until nt) f(i)
        case None =>
      }

      tail = nt // has to be < head or the size test would have failed
      _size -= n
    }
  }

  /**
    * move head backwards
    */
  def dropRight (n: Int): Unit = {
    if (_size <= n){
      clear
    } else {
      val nh = head - n
      cleanUp match {
        case Some(f) => for (i <- head until nh) f(i)
        case None =>
      }

      head = nh // has to be > tail or the size test would have failed
      _size -= n
    }
  }

  class ForwardIterator[T] (f: (Int)=>T) extends Iterator[T] {
    var j = tail

    override def hasNext: Boolean = j >= 0 && j <= head

    override def next(): T = {
      if (j < 0 || j > head) throw new NoSuchElementException("forward iteration past head")
      if (j < tail) j = tail // tail has moved

      val i = j % capacity
      j += 1
      f(i)
    }
  }

  class ReverseIterator[T] (f: (Int)=>T) extends Iterator[T] {
    var j = head

    override def hasNext: Boolean = j >= 0 && j >= tail

    override def next(): T = {
      if (j < 0 || j < tail) throw new NoSuchElementException("reverse iteration past tail")
      if (j > head) j = head // head has moved

      val i = j % capacity
      j -= 1
      f(i)
    }
  }
}

class CircularArray[T: Manifest](val data: Array[T], val cleanUp: Option[(Int)=>Unit]) extends CircularSeq {
  def this (len: Int, cf: Option[(Int)=>Unit] = None) = this(new Array[T](len), cf)
  val capacity = data.length

  def append(e: T): this.type = { data(incIndices) = e; this }
  def += (e: T): this.type = { data(incIndices) = e; this }

  def iterator = new ForwardIterator[T](data)
  def reverseIterator = new ReverseIterator[T](data)
}
