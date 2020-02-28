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
package gov.nasa.race.geo

import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Angle._

import scala.collection.mutable.ArrayBuffer

object LatLon {
  final val eps = 0.00000000001
  final val frac = 10000000.0

  @inline def apply (lat: Angle, lon: Angle): LatLon = new LatLon( encode(lat,lon))

  /**
    * double lat/lon encoding using the algorithm described in http://www.dupuis.me/node/35 that
    * stores lat/lon values in a 360*180 (64000: 2 bytes) grid with 7 digit offset precision
    * (9999999: 3 bytes) in each direction, which fits into a single 2+3+3=8 byte word. The resulting
    * position accuracy is about 10cm, which is sufficient for the purpose of tracking aircraft
    */
  final def encode (lat: Angle, lon: Angle): Long = {
    val latDeg = lat.toDegrees
    val lonDeg = lon.toDegrees

    val lonDegʹ = if (lonDeg == 180.0) lonDeg - eps else if (lonDeg == -180.0) lonDeg + eps else lonDeg

    val dlon = 180.0 + (if (lonDegʹ < -180.0) lonDegʹ + 360.0 else if (lonDegʹ > 180.0) lonDegʹ - 360.0 else lonDegʹ)
    val dlat = 90.0 + (if (latDeg < -90.0) latDeg + 180.0 else if (latDeg > 90.0) latDeg - 180.0 else latDeg)

    val grid = dlat.toInt * 360 + dlon.toInt
    val ilon = ((dlon - dlon.toInt) * frac).toInt
    val ilat = ((dlat - dlat.toInt) * frac).toInt

    var l: Long = 0
    l =  ((ilat >> 16) & 0xff) ; l <<= 8    // 56
    l += ((ilon >> 16) & 0xff) ; l <<= 16   // 48
    l += (ilat & 0xffff)       ; l <<= 16   // 32
    l += (ilon & 0xffff)       ; l <<= 16   // 16
    l += (grid & 0xffff)                    // 0

    l
  }
}
import gov.nasa.race.geo.LatLon._

/**
  * value class that encodes a geo position given as latitude and longitude
  *
  * This is not a uom quantity but is used closely with Angle and Length, and hence might be used
  * as the basis for GeoPosition in the future
  */
class LatLon protected[geo] (val l: Long) extends AnyVal {

  def lat: Angle = {
    val grid = (l & 0xffff).toInt
    val ilat = ((l >> 32) & 0xffff) + (((l >> 56) & 0xff).toInt << 16)
    Degrees( (grid / 360) + (ilat.toDouble / frac) - 90.0)
  }

  def lon: Angle = {
    val grid = (l & 0xffff).toInt
    val ilon = ((l >> 16) & 0xffff) + (((l >> 48) & 0xff).toInt << 16)
    Degrees( (grid % 360) + (ilon.toDouble / frac) - 180.0)
  }
}

// delegating iterator is unfortunately 3x slower due to indirection
//final class LatLonIterator (it: Iterator[Long]) extends Iterator[LatLon] {
//  @inline def hasNext: Boolean = it.hasNext
//  @inline def next: LatLon = new LatLon(it.next)
//}

final class LatLonArray protected[geo] (protected[geo] val data: Array[Long]) {

  class Iter extends Iterator[LatLon] {
    private var i = 0
    def hasNext: Boolean = i < data.length
    def next: LatLon = {
      if (i >= data.length) throw new NoSuchElementException
      val j = i
      i += 1
      new LatLon(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[LatLon] {
    private var i = data.length-1
    def hasNext: Boolean = i >= 0
    def next: LatLon = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new LatLon(data(j)) // should not allocate
    }
  }

  def this(len: Int) = this(new Array[Long](len))

  @inline def length: Int = data.length
  override def clone: LatLonArray = new LatLonArray(data.clone)

  def grow(newCapacity: Int): LatLonArray = {
    val newData = new Array[Long](newCapacity)
    System.arraycopy(data,0,newData,0,data.length)
    new LatLonArray(newData)
  }

  @inline def copyFrom(other: LatLonArray, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    System.arraycopy(other.data,srcIdx,data,dstIdx,len)
  }

  @inline def apply(i:Int): LatLon = new LatLon(data(i))
  @inline def update(i:Int, v: LatLon): Unit = data(i) = v.l

  @inline def iterator: Iterator[LatLon] = new Iter
  @inline def reverseIterator: Iterator[LatLon] = new ReverseIter

  // this is also much faster than using data.foreach, which adds another indirection
  def foreach(f: (LatLon)=>Unit): Unit = {
    var i = 0
    while (i < data.length) {
      f(new LatLon(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): LatLonArray = new LatLonArray(data.slice(from,until))
  @inline def tail: LatLonArray = new LatLonArray(data.tail)
  @inline def take (n: Int): LatLonArray = new LatLonArray(data.take(n))
  @inline def drop (n: Int): LatLonArray = new LatLonArray(data.drop(n))

  @inline def toBuffer: LatLonArrayBuffer = new LatLonArrayBuffer(ArrayBuffer.from(data))

  //.. and more to follow
}

final class LatLonArrayBuffer protected[geo] (protected[geo] val data: ArrayBuffer[Long]) {

  class Iter extends Iterator[LatLon] {
    private var i = 0
    def hasNext: Boolean = i < data.size
    def next: LatLon = {
      if (i >= data.size) throw new NoSuchElementException
      val j = i
      i += 1
      new LatLon(data(j)) // should not allocate
    }
  }
  class ReverseIter extends Iterator[LatLon] {
    private var i = data.size-1
    def hasNext: Boolean = i >= 0
    def next: LatLon = {
      if (i <0) throw new NoSuchElementException
      val j = i
      i -= 1
      new LatLon(data(j)) // should not allocate
    }
  }

  def this(capacity: Int) = this(new ArrayBuffer[Long](capacity))

  @inline def size: Int = data.size
  @inline def isEmpty: Boolean = data.isEmpty
  @inline def nonEmpty: Boolean = data.nonEmpty
  override def clone: LatLonArrayBuffer = new LatLonArrayBuffer(data.clone)

  @inline def += (v: LatLon): LatLonArrayBuffer = { data += v.l; this }
  @inline def append (vs: LatLon*): Unit = vs.foreach( data += _.l )

  @inline def apply(i:Int): LatLon = new LatLon(data(i))
  @inline def update(i:Int, v: LatLon): Unit = data(i) = v.l

  @inline def iterator: Iterator[LatLon] = new Iter
  @inline def reverseIterator: Iterator[LatLon] = new ReverseIter

  def foreach(f: (LatLon)=>Unit): Unit = {
    var i = 0
    while (i < data.size) {
      f(new LatLon(data(i)))
      i += 1
    }
  }

  @inline def slice(from: Int, until: Int): LatLonArrayBuffer = new LatLonArrayBuffer(data.slice(from,until))
  @inline def take (n: Int): LatLonArrayBuffer = new LatLonArrayBuffer(data.take(n))
  @inline def drop (n: Int): LatLonArrayBuffer = new LatLonArrayBuffer(data.drop(n))

  @inline def head: LatLon = new LatLon(data.head)
  @inline def tail: LatLonArrayBuffer = new LatLonArrayBuffer(data.tail)
  @inline def last: LatLon = new LatLon(data.last)

  def toArray: LatLonArray = new LatLonArray(data.toArray)

  //.. and more to follow
}




