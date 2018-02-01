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
package gov.nasa.race.cl.comp

import java.nio.ByteBuffer

import gov.nasa.race._
import gov.nasa.race.common._

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration.FiniteDuration

/**
  * a SmoothingExtrapolator that works on a set of entries, uses contiguous memory blocks, and hence can use OpenCL
  * devices for extrapolation
  *
  * This implementation assumes that - after a seed phase - the number of used (active) entries is fairly constant,
  * and the average number of changes within the extrapolation interval is small compared to the number of active
  * entries. In this case, compaction of entries is not efficient and we re-organize by means of maintaining a free list
  */
class BlockExtrapolator (val maxEntries: Int,             // maximum number of entries
                         val ΔtAverage: FiniteDuration,   // average update interval for all entries
                         val initValues: Array[Double],   // v0 - all init values / seeds arrays have to be of same length
                         val levelSeeds: Array[Double],   // α0
                         val trendSeeds: Array[Double]    // γ0
                        ) {

  assert(initValues.length == levelSeeds.length)
  assert(initValues.length == trendSeeds.length)

  // host-only data structure we need to locate entries within blocks, and to compute inBuffer values
  class Entry (val id: String,        // of entry (map key)
               val index: Int,        // into input and output blocks
               val α: Array[Double],  // level smoothing factors
               val γ: Array[Double]   // trend smoothing factors
              ){
    var tLast: Long = 0
    var nObservations: Int = 0
  }

  final val nStates = initValues.length

  // all values are doubles
  final val outRecordLength = 8 * 16*nStates  // tLast + nStates*(s,m)
  final val inRecordLength = 8 * nStates     // state estimates

  final val tscale = 10.0 ** Math.round(Math.log10(ΔtAverage.toMillis))

  var topIndex = -1 // highest used index
  var freeList = List.empty[Int]

  val entryMap: MHashMap[String,Entry] = MHashMap.empty // the host-only data per entry

  val outBuffer  = ByteBuffer.allocate(maxEntries * outRecordLength)   // host -> device
  val inBuffer = ByteBuffer.allocate(maxEntries * inRecordLength)  // device -> host

  //--- init outBuffer
  for (i <- 0 until maxEntries) {
    var r = i * outRecordLength
    outBuffer.putLong(r,0)
    for (j <- 0 until nStates) {
      r += 8;  outBuffer.putDouble(r, initValues(j))
      r += 8;  outBuffer.putDouble(r, 0)
    }
  }

  def size = entryMap.size

  private def getNewEntry (id: String): Entry = {
    val idx = if (freeList.isEmpty) {
      if (topIndex < maxEntries) {
        topIndex += 1
        topIndex
      } else {
        throw new RuntimeException("BlockExtrapolator entry size exceeded")
      }
    } else {
      val idx = freeList.head
      freeList = freeList.tail
      idx
    }

    new Entry(id,idx,levelSeeds.clone,trendSeeds.clone)
  }

  def addObservation (id: String, x: Array[Double], t: Long): Unit = {
    val e = entryMap.getOrElseUpdate(id,getNewEntry(id))
    val i0 = e.index * outRecordLength

    val nObservations = e.nObservations
    val tLast = e.tLast
    e.tLast = t
    e.nObservations += 1
    outBuffer.putLong(i0,t)

    if (nObservations == 0) { // first observation
      for (j <- 0 until nStates) {
        val is = i0 + 8 + (j*8)
        val im = is + 8
        outBuffer.putDouble(is,x(j))
        outBuffer.putDouble(im,0)
      }

    } else {
      val Δt = (t - tLast) / tscale
      if (Δt != 0) { // safe guard against duplicated observations that would cause infinity results
        for (j <- 0 until nStates) {

          //--- update entry
          var α = e.α(j)
          var γ = e.γ(j)
          α =  α / (α + (1 - α) ** Δt)
          γ = γ / (γ + (1 - γ) ** Δt)
          e.α(j) = α
          e.γ(j) = γ

          //--- update outBuffer
          val is = i0 + 8 + (j*8)
          val im = is + 8
          var s = outBuffer.getDouble(is)
          var m = outBuffer.getDouble(im)
          val sʹ = (1 - α) * (s + Δt * m) + α * x(j)
          m = (1 - γ) * m + γ * (sʹ - s) / Δt
          s = sʹ
          outBuffer.putDouble(i0,t)
          outBuffer.putDouble(is,s)
          outBuffer.putDouble(im,m)
        }
      }
    }
  }

  def removeEntry (id: String): Unit = {
    ifSome(entryMap.get(id)) { e=>
      val idx = e.index
      val i0 = e.index * outRecordLength

      for (j <- 0 to nStates) {
        outBuffer.putLong(i0 + j, 0)
      }

      if (idx == topIndex) {
        topIndex -= 1
      } else {
        freeList = idx :: freeList
      }
      entryMap -= id
    }
  }

  //--- debugging and testing (we normally use OpenCL devices to extrapolate

  def extrapolate (t: Long): Unit = {
    for (e: Entry <- entryMap.values) {
      val idx = e.index
      val i0 = idx * outRecordLength + 8
      val r0 = idx * inRecordLength

      for (j <- 0 until nStates) {
        val is = i0 + (j * 8)
        var s = outBuffer.getDouble(is)
        var m = outBuffer.getDouble(is+8)

        val v = s + (t - e.tLast) * m / tscale
        inBuffer.putDouble(r0 + (j*8),v)
      }
    }
  }

  def foreach (f: (Array[Double]=>Unit)): Unit = {
    val v = new Array[Double](nStates)

    for (e: Entry <- entryMap.values) {
      val idx = e.index
      val r0 = idx * inRecordLength

      for (j <- 0 until nStates) {
        v(j) = inBuffer.getDouble(r0 + (j*8))
      }

      f(v)
    }
  }
}
