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
package gov.nasa.race.track

import java.nio.ByteBuffer

import gov.nasa.race.common.{BufferRecord, LockableRecord}

object FloatTrackRecord {
  final val size = 44
}

/**
  * a generic BufferRecord for tracks that uses floats for floating point values
  */
class FloatTrackRecord (bbuf: ByteBuffer, recSize: Int, recOffset: Int) extends BufferRecord (recSize,bbuf,recOffset) {

  def this (bbuf: ByteBuffer) = this(bbuf,FloatTrackRecord.size,0)
  def this (bbuf: ByteBuffer, recOff: Int) = this(bbuf,FloatTrackRecord.size,recOff)

  val id   = char(0, 8)   // call sign or id (max 8 char)
  val date = long(8)      // epoch in millis

  val lat  = float(16)    // degrees
  val lon  = float(20)    // degrees
  val alt  = float(24)    // meters

  val hdg  = float(28)    // heading in degrees
  val spd  = float(32)    // ground speed in m/s
  val vr   = float(36)    // vertical rate in m/s

  val stat = int(40)      // status bit field
}


object LockableFloatTrackRecord {
  final val size = FloatTrackRecord.size + 4
}

class LockableFloatTrackRecord (bbuf: ByteBuffer, recOffset: Int)
                        extends FloatTrackRecord(bbuf,LockableFloatTrackRecord.size, recOffset) with LockableRecord {
  protected val lock = int(FloatTrackRecord.size)     // add lock field
}