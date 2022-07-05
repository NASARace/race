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

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
  * a mutable sparse 2D array that is accessed by indices
  *
  * TODO - this still needs a lot of operations (remove etc.)
  * TODO - this is implemented as a map row-maps, i.e. it assumes that there are not too many rows. Make more general
  */
class Sparse2DGrid[T: ClassTag] {

  protected var len: Int = 0
  protected var xMin: Int = Int.MaxValue
  protected var xMax: Int = Int.MinValue
  protected var yMin: Int = Int.MaxValue
  protected var yMax: Int = Int.MinValue

  protected val rows = mutable.Map.empty[Int, mutable.Map[Int,T]] // y-> {x->T}

  def clear(): Unit = {
    rows.clear()
    len = 0
    xMin = Int.MaxValue
    xMax = Int.MinValue
    yMin = Int.MaxValue
    yMax = Int.MinValue
  }

  def apply (x: Int, y: Int): T = rows(y)(x)

  def get (x: Int, y: Int): Option[T] = rows.get(y).flatMap( _.get(x))

  def getOrElseUpdate (x: Int, y: Int, op: =>T): T = {
    get(x,y) match {
      case Some(v) => v
      case None =>
        val v = op
        update(x,y,v)
        v
    }
  }

  def update (x: Int, y: Int, v: T): Unit = {
    if (x < 0) throw new ArrayIndexOutOfBoundsException("x < 0")
    if (y < 0) throw new ArrayIndexOutOfBoundsException("y < 0")

    if (x > xMax) xMax = x
    if (x < xMin) xMin = x
    if (y > yMax) yMax = y
    if (y < yMin) yMin = x

    val row = rows.getOrElseUpdate(y, mutable.Map.empty)
    val l = row.size
    row += (x->v)
    if (row.size > l) len += 1
  }

  // note this is unordered
  def foreach (f: T=>Unit): Unit = {
    rows.foreach( e=> e._2.foreach( e=> f(e._2)))
  }

  // not very efficient
  def foreachInRow (y: Int)(f: T=>Unit) = {
    rows.get(y) match {
      case Some(r) => r.foreach(e=> f(e._2))
      case None => // nothing there
    }
  }

  def foldLeft[B] (z:B)(op: (B,(Int,Int,T))=>B): B = {
    var res: B = z
    rows.foreach { e=>
      e._2.foreach { g=>
        res = op(res, (e._1, g._1, g._2))
      }
    }
    res
  }

  def yIterator: Iterator[Int] = rows.keysIterator

  def xIterator(y: Int): Iterator[Int] = {
    rows.get(y) match {
      case Some(r) => r.keysIterator
      case None => Iterator.empty[Int]
    }
  }

  def toSeq: Seq[T] = {
    val buf = new ArrayBuffer[T](len)
    foreach( buf += _)
    buf.toSeq
  }

  def toArray: Array[T] = {
    val buf = new ArrayBuffer[T](len)
    foreach( buf += _)
    buf.toArray
  }

  def isEmpty: Boolean = len == 0
  def nonEmpty: Boolean = len > 0
  def size: Int = len

  def minX: Int = xMin
  def maxX: Int = xMax
  def minY: Int = yMin
  def maxY: Int = yMax
}
