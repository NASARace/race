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
package gov.nasa.race.uom

import DateTime._
import Time._

/**
  * Date array that is stored as elapsed time from first entry
  * this is a memory efficient way to store a time series that is limited to <= 24d
  */
final class DeltaDateArray protected[uom] (protected[uom] val elapsed: TimeArray,
                                           protected[uom] var date0: DateTime = UndefinedDateTime) {
  class Iter extends Iterator[DateTime] {
    private var i = 0
    def hasNext: Boolean = i < elapsed.length
    def next(): DateTime = {
      if (i >= elapsed.length) throw new NoSuchElementException
      val j = i
      i += 1
      date0 + elapsed(j) // should not allocate
    }
  }
  class ReverseIter extends Iterator[DateTime] {
    private var i = elapsed.length-1
    def hasNext: Boolean = i >= 0
    def next(): DateTime = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      date0 + elapsed(j) // should not allocate
    }
  }

  def this(len: Int) = this(new TimeArray(len))

  def length: Int = elapsed.length
  override def clone: DeltaDateArray = new DeltaDateArray(elapsed.clone, date0)

  def grow(newCapacity: Int): DeltaDateArray = {
    val newElapsed = elapsed.grow(newCapacity)
    new DeltaDateArray(newElapsed,date0)
  }

  @inline def apply(i: Int) = date0 + elapsed(i)

  @inline def update(i:Int, d: DateTime): Unit = {
    if (date0.isUndefined) {
      date0 = d
      elapsed(i) = Time0
    } else {
      elapsed(i) = date0 timeUntil d
    }
  }

  @inline def iterator: Iterator[DateTime] = new Iter
  @inline def reverseIterator: Iterator[DateTime] = new ReverseIter

  def foreach(f: (DateTime)=>Unit): Unit = {
    var i = 0
    while (i < elapsed.length) {
      f(date0 + elapsed(i))
      i += 1
    }
  }

  @inline def copyFrom(other: DeltaDateArray, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    var tAdjust: Time = UndefinedTime
    if (date0 != other.date0) {
      if (date0.isUndefined) {
        date0 = other.date0
      } else {
        tAdjust = date0.timeSince(other.date0)
      }
    }

    System.arraycopy(other.elapsed.data,srcIdx,elapsed.data,dstIdx,len)

    if (tAdjust.isDefined){
      var i = dstIdx
      val i1 = dstIdx + len
      while (i < i1) {
        elapsed(i) += tAdjust
        i += 1
      }
    }
  }

  @inline def slice(from: Int, until: Int): DeltaDateArray = new DeltaDateArray(elapsed.slice(from,until))
  @inline def tail: DeltaDateArray = new DeltaDateArray(elapsed.tail)
  @inline def take (n: Int): DeltaDateArray = new DeltaDateArray(elapsed.take(n))
  @inline def drop (n: Int): DeltaDateArray = new DeltaDateArray(elapsed.drop(n))

  def toMillisecondArray: Array[Long] = {
    val a = new Array[Long](elapsed.length)
    for (i <- 0 until elapsed.length) a(i) = (date0 + elapsed(i)).toEpochMillis
    a
  }

  def toBuffer: DeltaDateArrayBuffer = new DeltaDateArrayBuffer(elapsed.toBuffer,date0)

  //... and more to follow
}

final class DeltaDateArrayBuffer protected[uom] (protected[uom] val elapsed: TimeArrayBuffer,
                                                 protected[uom] var date0: DateTime = UndefinedDateTime) {

  class Iter extends Iterator[DateTime] {
    private var i = 0
    def hasNext: Boolean = i < elapsed.size
    def next(): DateTime = {
      if (i >= elapsed.size) throw new NoSuchElementException
      val j = i
      i += 1
      date0 + elapsed(j) // should not allocate
    }
  }
  class ReverseIter extends Iterator[DateTime] {
    private var i = elapsed.size-1
    def hasNext: Boolean = i >= 0
    def next(): DateTime = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      date0 + elapsed(j) // should not allocate
    }
  }

  def this(capacity: Int) = this(new TimeArrayBuffer(capacity), UndefinedDateTime)

  @inline def size: Int = elapsed.size
  @inline def isEmpty: Boolean = elapsed.isEmpty
  @inline def nonEmpty: Boolean = elapsed.nonEmpty
  override def clone: DeltaDateArrayBuffer = new DeltaDateArrayBuffer(elapsed.clone, date0)

  @inline def startDate: DateTime = date0 // not necessarily the same as head (elements can be dropped)

  @inline def += (d: DateTime): Unit = {
    if (date0.isUndefined){
      date0 = d
      elapsed += Time0
    } else {
      elapsed += date0 timeUntil d
    }
  }
  @inline def append (ds: DateTime*): Unit = {
    if (date0.isUndefined) date0 = ds.head
    ds.foreach( d => elapsed += date0 timeUntil d )
  }

  @inline def apply(i:Int): DateTime = date0 + elapsed(i)
  @inline def update(i:Int, d: DateTime): Unit = {
    if (i < 0 || i >= elapsed.size) throw new IndexOutOfBoundsException(i.toString)
    if (date0.isUndefined){
      date0 = d
      elapsed(i) = Time0
    } else {
      elapsed(i) = date0 timeUntil d
    }
  }

  @inline def iterator: Iterator[DateTime] = new Iter
  @inline def reverseIterator: Iterator[DateTime] = new ReverseIter

  def foreach(f: (DateTime)=>Unit): Unit = {
    var i = 0
    while (i < elapsed.size) {
      f(date0 + elapsed(i))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): DeltaDateArrayBuffer = new DeltaDateArrayBuffer(elapsed.slice(from,until),date0)
  @inline def take (n: Int): DeltaDateArrayBuffer = new DeltaDateArrayBuffer(elapsed.take(n),date0)
  @inline def drop (n: Int): DeltaDateArrayBuffer = new DeltaDateArrayBuffer(elapsed.drop(n),date0)

  @inline def head: DateTime = date0 + elapsed.head
  @inline def tail: DeltaDateArrayBuffer = new DeltaDateArrayBuffer(elapsed.tail,date0)
  @inline def last: DateTime = date0 + elapsed.last

  //def sort: Unit = SeqUtils.quickSort(elapsed)

  def toArray: DeltaDateArray = new DeltaDateArray(elapsed.toArray,date0)

  //... and more to follow

}