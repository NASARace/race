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
package gov.nasa.race.cl.comp

import java.nio.ByteBuffer

import gov.nasa.race.cl.CLCommandQueue
import gov.nasa.race.common._
import gov.nasa.race.common.BufferRecord
import gov.nasa.race.track.Tracked3dObject

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration.FiniteDuration

class TrackSERecord (buffer: ByteBuffer) extends BufferRecord(128,buffer) {
  val tLast = long(0) // epoch msec of last fix

  val sLat  = double(8)
  val mLat  = double(16)
  val lat   = double(24)  // estimate

  val sLon  = double(32)
  val mLon  = double(40)
  val lon   = double(48)

  val sAlt  = double(56)
  val mAlt  = double(64)
  val alt   = double(72)

  val sSpd  = double(80)
  val mSpd  = double(88)
  val spd   = double(96)

  val sHdg  = double(104)
  val mHdg  = double(112)
  val hdg   = double(120)
}

class Entry (val id: String,        // of entry (map key)
             val index: Int,        // into input and output blocks
             val α: Array[Double],  // level smoothing factors
             val γ: Array[Double]   // trend smoothing factors
            ){
  var tLast: Long = 0
  var nObservations: Int = 0
}

class TrackBlockExtrapolator (val queue: CLCommandQueue,
                              val maxTracks: Int,              // maximum number of tracks
                              val ΔtAverage: FiniteDuration,   // average update interval for all entries
                              val α0: Double,                  // level seed
                              val γ0: Double                   // trend seed
                             ) {
  val tScale = 10.0 ** Math.round(Math.log10(ΔtAverage.toMillis.toDouble)).toDouble
  var topIndex = -1 // highest used block record index (blocks are dense - we move the highest entry upon removal)

  val seBuffer = queue.context.createMappedRecordBuffer(maxTracks, new TrackSERecord(_))

  val entryMap: MHashMap[String,Entry] = MHashMap.empty // track map: id -> index (+host data)
  val idxMap: MHashMap[Int,String] = MHashMap.empty     // reverse map: index -> id

  def size = entryMap.size
  def map = seBuffer.enqueueMap(queue) // has to be called before we can add observations (fixes)
  def unmap = seBuffer.enqueueUnmap(queue)

  def addObservation (track: Tracked3dObject) = {
    entryMap.get(track.id) match {
      case Some(e) =>
      case None =>
    }
  }
}
