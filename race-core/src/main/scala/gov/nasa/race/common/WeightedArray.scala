/*
 * Copyright (c) 2016, United States Government, as represented by the
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

import java.util
import java.util.Comparator

import gov.nasa.race.util.ArraySliceIterator

import scala.annotation.tailrec
import scala.collection.Iterable

object WeightedArray {
  trait Entry[T] {
    var obj: T
    var weight: Double

    @inline def set(o: T, w: Double): Unit = {
      obj = o
      weight = w
    }

    override def toString = s"($weight,$obj)"
  }

  class EntryComparator[E <: Entry[_]] extends Comparator[E] {
    override def compare (a: E, b: E): Int = {
      val wa: Double = a.weight
      val wb: Double = b.weight
      if (wa < wb) -1
      else if (wa == wb) 0
      else  1
    }
  }
}

/**
  * bounded array trait to keep ordered sets of objects
  * Each object has an associated weight (double) that is provided by the caller,
  * all objects in the buffer are ordered by increasing weight.
  *
  * the concrete object and entry type has to be provided by the concrete class. The
  * entry type is generic to support additional context information
  *
  * this implementation is based on three assumptions:
  *  - the max buffer size is small (<50)
  *  - updates can happen with a high frequency, hence each
  *    update and successive iteration(s) should execute in constant space (no heap)
  *  - thread safety is guaranteed by the caller
  */
trait WeightedArray[T<:AnyRef,E<:WeightedArray.Entry[T]] extends Iterable[E] {

  protected val array: Array[E]                                     // provided by concrete type
  protected var _size = 0
  protected val comparator = new WeightedArray.EntryComparator[E]()

  //--- entry management)
  def createEntry (obj: T, weight: Double): E             // has to be provided by concrete types
  def isSame (obj1: T, obj2: T): Boolean = obj1 == obj2   // can be overridden to correlate type
  def setEntry(e: E, obj: T, weight: Double): Unit = e.set(obj,weight)
  def releaseEntry(e: E): Unit = {}                       // entry is dropped (can be used to update cache)
  def updateEntry (e: E): Boolean = false                 // can be overridden to recompute weight etc.

  def clear: Unit = {
    _size = 0
  }

  override def size: Int = _size
  def notEmpty: Boolean = _size > 0
  override def isEmpty: Boolean = _size == 0

  @tailrec final protected def _indexOfSame(a: Array[E], n: Int, o: T, i: Int): Int = {
    if (i < n) {
      if (isSame(o, a(i).obj)) i
      else _indexOfSame(a, n, o, i + 1)
    } else -1
  }

  @tailrec final protected def _indexExceeding (a: Array[E], n: Int, w: Double, i: Int): Int = {
    if (i<n) {
      if (a(i).weight > w) i
      else _indexExceeding(a, n, w, i+1)
    } else -1
  }

  @tailrec final protected def _sortIn(a: Array[E], n: Int, obj: T, weight: Double, i: Int): Int = {
    val e = a(i)
    val iMax = n-1

    if (isSame(obj, e.obj)){
      if (i == iMax || weight <= a(i+1).weight) {
        // update in situ, no need to shift - this should be the main path for small changes
        setEntry(e, obj, weight)
        i
      } else { // object is going to shift up
        System.arraycopy(a, i+1, a, i, iMax-i) // shift tail down
        setEntry(e,obj,weight)
        val i1 = _indexExceeding(a, n-1, weight, i)
        if (i1 < 0) { // shift to last position
          a(iMax) = e
          iMax
        } else { // sort into tail
          System.arraycopy(a,i1,a,i1+1,iMax-i1)
          a(i1) = e
          i1
        }
      }
    } else if (weight < e.weight) {
      val i1 = _indexOfSame(a, n, obj, i+1)
      if (i1 > 0) { // we already have a corresponding object
        val e1 = a(i1)
        if (i1 < iMax) System.arraycopy(a, i1 + 1, a, i1, iMax - i1) // shift i1]-tail down
        System.arraycopy(a, i, a, i+1, iMax - i) // shift [i-tail up
        setEntry(e1, obj, weight)
        a(i) = e1
      } else { // we don't have this object yet
        if (i == a.length-1) { // last entry gets replaces
          releaseEntry(e)
        } else { // we sort in a new entry
          if (n == a.length) {
            releaseEntry(a(iMax))
            System.arraycopy(a, i, a, i+1, n-i-1)
          } else {
            _size += 1
            System.arraycopy(a, i, a, i+1, n-i) // shift up to make room
          }
        }
        a(i) = createEntry(obj,weight)
      }
      i
    } else {
      if (i == iMax) -1 else _sortIn(a, n, obj, weight, i + 1)
    }
  }

  /**
    * add a new weighted object - if there is still space in the array
    *
    * @param obj the object that gets weighted
    * @param weight the weight that defines the order
    * @return index at which element got sorted in, or -1 if it didn't make the cut
    */
  def tryAdd(obj: T, weight: Double): Int = {
    if (_size == 0) { // trivial case, first entry
      array(0) = createEntry(obj, weight)
      _size = 1
      0

    } else {
      val i = _sortIn(array, _size, obj, weight, 0)
      if (i >= 0) { // we sorted it in
        i
      } else if (_size < array.length) { // size limit not yet reached, append new entry
        val i = _size
        array(i) = createEntry(obj, weight)
        _size += 1
        i
      } else { // we are already at the size limit, ignore
        -1
      }
    }
  }

  def remove (obj: T): Int = {
    @tailrec def _remove (a: Array[E], len: Int, o: T, i: Int): Int = {
      if (i < len){
        val e = a(i)
        if (isSame(o, e.obj)) {
          _size -= 1
          System.arraycopy(a, i+1, a, i, len-i-1)
          releaseEntry(e)
          i
        } else {
          _remove(a,len,o,i+1)
        }
      } else {
        -1
      }
    }

    _remove(array, _size, obj, 0)
  }

  def removeEvery(f: E=>Boolean) = _remove(f,true)
  def removeFirst(f: E=>Boolean) = _remove(f,false)

  private def _remove(f: E=>Boolean, removeAll: Boolean): Boolean = {
    @tailrec def _filter (a: Array[E], len: Int, i: Int, entryChanged: Boolean): Boolean = {
      if (i < len) {
        val e = a(i)
        if (f(e)) {
          System.arraycopy(a,i+1,a,i,len-i-1)
          releaseEntry(e)
          _size -= 1
          if (removeAll) _filter(a,len-1,i,true) else true
        } else _filter(a,len,i+1,entryChanged)
      } else entryChanged
    }
    _filter(array,_size,0,false)
  }

  def updateWeights = {
    var entryChanged = false
    @tailrec def _updateWeight (a: Array[E], len: Int, i: Int): Unit = {
      if (i < len) {
        if (updateEntry(a(i))) entryChanged = true
        _updateWeight(a, len, i+1)
      }
    }

    _updateWeight(array, _size, 0)
    if (entryChanged) util.Arrays.sort(array, comparator)
  }

  def updateObjects (f: E=>T): Boolean = {
    var entryChanged = false
    @tailrec def _updateObject (a: Array[E], len: Int, i: Int): Unit = {
      if (i < len) {
        val e = a(i)
        val o = f(e)
        if (o ne e.obj) {
          e.obj = o
          entryChanged = true
        }
        _updateObject(a,len,i+1)
      }
    }

    _updateObject(array,_size,0)
    entryChanged
  }

  def updateWeights (f: E=>Double): Boolean = {
    var entryChanged = false
    @tailrec def _updateWeight (a: Array[E], len: Int, i: Int): Unit = {
      if (i < len) {
        val e = a(i)
        val w = f(e)
        if (w != e.weight) {
          entryChanged = true
          e.weight = w
        }
        _updateWeight(a, len, i+1)
      }
    }

    _updateWeight(array, _size, 0)
    if (entryChanged) util.Arrays.sort(array, 0, _size, comparator)
    entryChanged
  }


  def weightCut (maxWeight: Double) = {
    @tailrec def _removeTail (a: Array[E], len: Int, i: Int): Unit = {
      if (i < len){
        releaseEntry(a(i))
        _removeTail(a, len, i+1)
      }
    }

    @tailrec def _checkWeight (a: Array[E], len: Int, i: Int): Unit = {
      if (i < len) {
        if (a(i).weight > maxWeight) {
          _removeTail(a, _size, i)
          _size = i
        } else _checkWeight(a, len, i+1)
      }
    }

    _checkWeight(array, _size, 0)
  }

  //--- minimal iteration interface

  override def iterator: Iterator[E] = new ArraySliceIterator[E](array,0,_size)

  def apply (i: Int): E = {
    if (i>=0 && i<_size) array(i)
    else throw new ArrayIndexOutOfBoundsException(s"$i outside [0..${_size-1}]")
  }

  // constant space iteration (no Iterator object required)
  def foreachEntry (f: E => Unit) = {
    @tailrec def _visit (a: Array[E],  len: Int, i: Int, f: E=>Any): Unit = {
      if (i < len) {
        f(a(i))
        _visit(a, len, i+1, f)
      }
    }
    _visit(array, _size, 0, f)
  }

  override def toString = mkString(getClass.getSimpleName + " [", ",", "]")
}
