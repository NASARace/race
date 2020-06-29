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
package gov.nasa.race.uom

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

/*
  TODO - check if specialization really has an impact on inlining or if
  we can factor the redundancy out into a Seq[Double] based trait.
  Note that Array[T] in Scala does not directly implement Seq[T] but
  relies on a implicit scala.collection.mutable.WrappedArray[T] conversion
*/

object UOMDoubleArray {
  def initData (a: Array[Double], factor: Double): Array[Double] = {
    val data = a.clone()
    var i = 0
    val n = data.length
    while (i<n) {
      data(i) *= factor
      i += 1
    }
    data
  }
}

/**
  * abstract base for uom arrays that map to Double elements
  * unfortunately we can't have a generic Fractional storage type since trait type args can't have context bounds
  */
trait UOMDoubleArray[T] {
  type Self
  type SelfBuffer

  protected val data: Array[Double]

  @inline def length: Int = data.length
  @inline def size: Int = length
  @inline def isEmpty: Boolean = length == 0
  @inline def nonEmpty: Boolean = length > 0

  protected def toDoubleArray (quot: Double): Array[Double] = {
    val a = data.clone()
    val n = a.length
    var i = 0
    while (i<n) {
      a(i) /= quot
      i += 1
    }
    a
  }

  def apply(i:Int): T
  def update(i:Int, a: T): Unit

  def grow(newLength: Int): Self
  def copyFrom(other: Self, srcIdx: Int, dstIdx: Int, len: Int): Unit

  def slice(from: Int, until: Int): Self
  def tail: Self
  def take (n: Int): Self
  def drop (n: Int): Self

  def iterator: Iterator[T]
  def reverseIterator: Iterator[T]
  def foreach (f: (T)=>Unit): Unit

  def last: T
  def exists(p: (T)=>Boolean): Boolean
  def count(p: (T)=>Boolean): Int
  def max: T
  def min: T

  def toBuffer: SelfBuffer
}

object UOMDoubleArrayBuffer {
  def initData (as: Seq[Double], factor: Double): ArrayBuffer[Double] = {
    val n = as.length
    val data = new ArrayBuffer[Double](n)
    var i = 0
    while (i < n) {
      data.update(i, as(i) * factor)
      i += 1
    }
    data
  }

  def initData (as: Seq[Double]): ArrayBuffer[Double] = {
    val n = as.length
    val data = new ArrayBuffer[Double](n)
    var i = 0
    while (i < n) {
      data.update(i, as(i))
      i += 1
    }
    data
  }
}

/**
  * abstract base fpr UOM array types that map to Double elements
  * unfortunately we can't have a generic Fractional storage type since trait type args can't have context bounds
  */
trait UOMDoubleArrayBuffer[T] {
  type Self
  type SelfArray

  protected val data: ArrayBuffer[Double]

  @inline def length: Int = data.length
  @inline def size: Int = length
  @inline def isEmpty: Boolean = length == 0
  @inline def clear: Unit = data.clear()

  protected def toDoubleArray (quot: Double): Array[Double] = {
    val a = data.toArray
    val n = a.length
    var i = 0
    while (i<n) {
      a(i) /= quot
      i += 1
    }
    a
  }

  def iterator: Iterator[T]
  def reverseIterator: Iterator[T]
  def foreach (f: (T)=>Unit): Unit

  def += (a:T): Self
  def append (as: T*): Unit

  def apply(i:Int): T
  def update(i:Int, a: T): Unit

  def slice(from: Int, until: Int): Self
  def tail: Self
  def take (n: Int): Self
  def drop (n: Int): Self

  def last: T
  def exists(p: (T)=>Boolean): Boolean
  def count(p: (T)=>Boolean): Int
  def max: T
  def min: T

  def toArray: SelfArray
}
