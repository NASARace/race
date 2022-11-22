/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import scala.reflect.ClassTag

object NearestLookupTable {
  val DOWN = -1
  val NEAREST = 0
  val UP = 1

  def from[T<:AnyVal,V] (seq: Seq[(T,V)])(implicit num: Numeric[T]): NearestLookupTable[T,V] = {
    new NearestLookupTable(Array.from(seq))
  }

  def from[T<:AnyVal,V] (a: Array[(T,V)])(implicit num: Numeric[T]): NearestLookupTable[T,V] = {
    new NearestLookupTable(Array.from(a.clone())) // we sort so we need to copy
  }

  def from[T<:AnyVal,V:ClassTag] (seq: Seq[V], keyFunc: (V)=>T)(implicit num: Numeric[T]): NearestLookupTable[T,V] = {
    new NearestLookupTable(Array.from(seq).map( e=> (keyFunc(e),e)))
  }

  def from[T<:AnyVal,V] (a: Array[V], keyFunc: (V)=>T)(implicit num: Numeric[T]): NearestLookupTable[T,V] = {
    new NearestLookupTable(Array.from(a.map(  e=> (keyFunc(e),e))))
  }
}
import NearestLookupTable._

import scala.reflect.ClassTag

/**
 * nearest entry lookup table for (T,V) value pairs
 *
 * this implementation uses a simple binary search over a sorted array of (T,V) tuples
 */
class NearestLookupTable[T<:AnyVal, V](es: Array[(T,V)])(implicit num: Numeric[T]) {

  val elems = es.sortWith((a, b) => num.lt(a._1, b._1))

  def findLessOrEqual(t: T): (T,V) = find(t,DOWN)
  def findGreaterOrEqual(t: T): (T,V) = find(t,UP)
  def findNearest (t: T): (T,V) = find(t,NEAREST)

  def find(t: T, direction: Int): (T,V) = {
    @inline def closest(a: (T,V), b: (T,V), t: T): (T,V) = {
      if (direction < 0 ) a
      else if (direction > 0) b
      else {
        val da = num.minus(t, a._1)
        val db = num.minus(b._1, t)
        if (num.gteq(da, db)) b else a
      }
    }

    val n = elems.length
    val n1 = n-1

    if (num.equiv(t, elems(0)._1)) return elems(0)
    if (num.equiv(t, elems(n1)._1)) return elems(n1)

    if (num.lt( t, elems(0)._1)) {
      if (direction == UP || direction == NEAREST) return elems(0) else throw new NoSuchElementException(s"$t less than first element")
    }
    if (num.gt( t, elems(n1)._1)) {
      if (direction == DOWN || direction == NEAREST) return elems(n1) else throw new NoSuchElementException(s"$t greater than last element")
    }

    var i = 0  // left index
    var j = n  // right index
    var m = 0  // mid index

    while (i < j) {
      m = (i + j) / 2
      if (num.equiv(elems(m)._1, t)) return elems(m)

      if (num.lt( t, elems(m)._1)) {
        if (m > 0 && num.gt( t, elems(m-1)._1)) return closest(elems(m-1), elems(m), t)
        j = m
      } else {
        if (m < n-1 && num.lt(t, elems(m+1)._1)) return closest(elems(m), elems(m+1), t)
        i = m+1
      }
    }

    elems(m)
  }

  def foreach( f: ((T,V))=>Unit): Unit = elems.foreach( f)
  def size: Int = elems.length
  def isEmpty: Boolean = elems.length > 0

  def first: (T,V) = elems.head
  def last: (T,V) = elems.last
  def isWithinRange (t: T): Boolean = num.gteq(t, elems.head._1) && num.lteq(t,elems.last._1)
}
