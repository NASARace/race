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

import gov.nasa.race.common.inlined.Slice

/**
  * an iterator over sub-slices that are delimited by ASCII characters
  *
  * Note that the returned slice is mutated so it should not be stored over iteration cycles
  *
  * Note also that the separator char is a value terminator, i.e. it terminates the *preceding*
  * substring. This means a leading separator (",...") is reported as an empty Slice, a trailing separator ("...,")
  * is not (same as Java's String.split)
  */
class SliceSplitter (var sep: Byte, var src: Slice) extends Iterator[Slice] {
  val subSlice: Slice = new Slice(src.data,0,0)
  var idx = src.offset
  var limit = src.offset + src.length

  def this (sep: Byte) = this(sep, Slice.empty)

  def setSource(newSrc: Slice): Unit = {
    src = newSrc
    subSlice.set(newSrc.data,0,0)
    idx = src.offset
    limit = src.offset + src.length
  }

  def setSeparator (newSep: Byte): Unit = {
    sep = newSep
    subSlice.length = 0
  }

  final def hasNext: Boolean = {
    idx < limit
  }

  /**
    * note that consecutive separator chars are reported as empty slices
    */
  def next: Slice = {
    val bs = src.data
    val limit = this.limit
    var i = idx
    val i0 = i
    while (i < limit) {
      if (bs(i) == sep) {
        idx = i+1
        subSlice.setRange(i0,i-i0)
        return subSlice
      }
      i += 1
    }

    idx = limit
    if (limit > i0) {
      subSlice.setRange(i0,limit-i0)
      subSlice
    } else {
      throw new NoSuchElementException
    }
  }
}
