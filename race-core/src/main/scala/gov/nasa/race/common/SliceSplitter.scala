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
  * an iterator over sub-slices that are delimited by byte constants
  *
  * Note that the returned slice is mutated so it should not be stored over iteration cycles
  *
  * Note also that the separator char is a value terminator, i.e. it terminates the *preceding*
  * substring. This means a leading separator (",...") is reported as an empty Slice, a trailing separator ("...,")
  * is not (same as Java's String.split)
  */
class SliceSplitter (var sep: Byte, var src: ByteSlice) extends Iterator[ByteSlice] {
  val subSlice: MutByteSlice = new MutRawByteSlice(src.data,0,0)
  var idx = src.off
  var limit = src.off + src.len

  def this (sep: Byte) = this(sep, MutRawByteSlice.empty)

  def setSource(newSrc: ByteSlice): SliceSplitter = {
    src = newSrc
    idx = src.off
    limit = src.off + src.len
    this
  }

  def setSeparator (newSep: Byte): SliceSplitter = {
    sep = newSep
    this
  }

  final def hasNext: Boolean = {
    idx < limit
  }

  /**
    * note that consecutive separator chars are reported as empty slices
    */
  def next(): ByteSlice = next(subSlice)

  /**
    * alternative accessor that uses a caller provided slice object
    */
  def next[T<:MutByteSlice] (slice: T): T = {
    slice.clear()
    val bs = src.data
    val limit = this.limit
    var i = idx
    val i0 = i
    while (i < limit) {
      if (bs(i) == sep) {
        idx = i+1
        slice.set(bs,i0,i-i0)
        return slice
      }
      i += 1
    }

    idx = limit
    if (limit > i0) {
      slice.set(bs,i0,limit-i0)
      slice
    } else {
      throw new NoSuchElementException
    }
  }

  def foreach[T<:MutByteSlice] (slice: T)(f: T=>Unit): Unit = {
    while (hasNext){
      f(next(slice))
    }
  }

  def foreachMatch[T<:MutByteSlice] (slice: T)(pf: PartialFunction[T,Unit]): Unit = {
    while (hasNext){
      pf(next(slice))
    }
  }
}
