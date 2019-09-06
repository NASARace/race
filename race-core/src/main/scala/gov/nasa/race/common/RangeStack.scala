/*
 * Copyright (c) 2019, United States Government, as represented by the
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
  * a minimal stack that stores offset and length values as Array[Int] to avoid runtime allocation
  */
class RangeStack (var capacity: Int) {
  var offsets: Array[Int] = new Array[Int](capacity)
  var lengths: Array[Int] = new Array[Int](capacity)
  var top: Int = -1

  @inline final def size: Int = top+1
  @inline final def isEmpty: Boolean = top < 0
  @inline final def nonEmpty: Boolean = top >= 0

  protected def grow (newCapacity: Int): Unit = {
    val newOffsets = new Array[Int](newCapacity)
    val newLengths = new Array[Int](newCapacity)

    val len = top+1
    System.arraycopy(offsets,0,newOffsets,0,len)
    System.arraycopy(lengths,0,newLengths,0,len)

    offsets = newOffsets
    lengths = newLengths
    capacity = newCapacity
  }

  def clear: Unit = {
    top = -1
  }

  def pop: Unit = {
    if (top >= 0) {
      top -= 1
    }
  }

  def isTop (off: Int, len: Int): Boolean = {
    top >= 0 && offsets(top) == off && lengths(top) == len
  }

  @inline final def topOffset: Int = offsets(top)
  @inline final def topLength: Int = lengths(top)

  def push (off: Int, len: Int): Unit = {
    top += 1
    if (top >= capacity) grow(capacity*2)
    offsets(top) = off
    lengths(top) = len
  }

  def foreach (f: (Int,Int,Int)=> Unit): Unit = {
    var i = 0
    while (i <=top) {
      f(i,offsets(i),lengths(i))
      i += 1
    }
  }

  def foreachReverse (f: (Int,Int,Int)=> Unit): Unit = {
    var i = top
    while (i >= 0) {
      f(i,offsets(i),lengths(i))
      i -= 1
    }
  }

  def exists (f: (Int,Int)=> Boolean): Boolean = {
    var i = 0
    while (i <=top) {
      if (f(offsets(i),lengths(i))) return true
      i += 1
    }
    false
  }
}

/**
  * a RangeStack that adds a Long hash per element
  */
class HashedRangeStack (initCapacity: Int) extends RangeStack(initCapacity) {
  var hashes: Array[Long] = new Array[Long](capacity)

  protected override def grow (newCapacity: Int): Unit = {
    super.grow(newCapacity)

    val newHashes = new Array[Long](newCapacity)
    System.arraycopy(hashes,0,newHashes,0,top+1)
    hashes = newHashes
  }

  @inline final def topHash: Long = hashes(top)

  override def push (off: Int, len: Int): Unit = {
    throw new RuntimeException("missing hash argument") // todo - not very scalatic
  }

  def push (off: Int, len: Int, hash: Long): Unit = {
    super.push(off,len)
    hashes(top) = hash
  }

  def isTop (off: Int, len: Int, hash: Long): Boolean = {
    top >= 0 && hashes(top) == hash && offsets(top) == off && lengths(top) == len
  }

  def foreach (f: (Int,Int,Int,Long)=> Unit): Unit = {
    var i = 0
    while (i <=top) {
      f(i,offsets(i),lengths(i),hashes(i))
      i += 1
    }
  }

  def foreachReverse (f: (Int,Int,Int,Long)=> Unit): Unit = {
    var i = top
    while (i >= 0) {
      f(i, offsets(i), lengths(i), hashes(i))
      i -= 1
    }
  }

  def exists (f: (Int,Int,Long) => Boolean): Boolean = {
    var i = 0
    while (i <=top) {
      if (f(offsets(i),lengths(i),hashes(i))) return true
      i += 1
    }
    false
  }
}