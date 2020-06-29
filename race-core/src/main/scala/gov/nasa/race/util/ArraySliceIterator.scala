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

package gov.nasa.race.util

/**
  * a generic iterator over slices of arrays
  * lower index bound is inclusive
  * upper index bound is exclusive
  */
class ArraySliceIterator[E] (array: Array[E], i0: Int, i1: Int) extends Iterator[E] {
  private var i=i0

  if (i0 < 0 || i0 >= array.length || i1 < 0 || i1 > array.length) throw new IllegalArgumentException

  override def hasNext: Boolean = i < i1
  override def next(): E = {
    if (i < i1) {
      val e = array(i)
      i += 1
      e
    } else throw new NoSuchElementException
  }
}
