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

import java.nio.{ByteBuffer, ByteOrder}

import gov.nasa.race.common.{BufferRecord, LockableRecord}


/**
  * a BufferRecord for tracks that uses double precision for floating point values
  */
object TrackRecord {
  final val size = 76

  def apply(bbuf: ByteBuffer, recOffset: Int) = new TrackRecord(bbuf,size,recOffset)
  def apply(recOffset: Int, createBuffer: Int=>ByteBuffer) = new TrackRecord(createBuffer(size),size,recOffset)
  def apply(recOffset: Int, nRecords: Int) = {
    new TrackRecord(ByteBuffer.allocate(recOffset + (nRecords*size)).order(ByteOrder.nativeOrder), size,recOffset)
  }
}

class TrackRecord (bbuf: ByteBuffer, recSize: Int, recOffset: Int) extends BufferRecord (recSize,bbuf,recOffset) {

  def this (bbuf: ByteBuffer) = this(bbuf,TrackRecord.size,0)
  def this (bbuf: ByteBuffer, recOff: Int) = this(bbuf,TrackRecord.size,recOff)

  val id   = char(0, 8)    // id (max 8 char) - channel specific (usually externally assigned track number)
  val cs   = char(8, 8)    // call sign (max 8 char) - might be assigned later
  val date = long(16)      // epoch in millis
  val stat = int(24)       // status bit field

  val lat  = double(28)    // degrees
  val lon  = double(36)    // degrees
  val alt  = double(44)    // meters

  val hdg  = double(52)    // heading in degrees
  val spd  = double(60)    // ground speed in m/s
  val vr   = double(68)    // vertical rate in m/s
}


/**
  * a BufferRecord for tracks that uses floats for floating point values, which significantly
  * reduces storage requirements for large number of tracks while still providing <1m positional
  * accuracy
  */
object FloatTrackRecord {
  final val size = 52

  def apply(bbuf: ByteBuffer, recOffset: Int) = new FloatTrackRecord(bbuf,size,recOffset)
  def apply(recOffset: Int, createBuffer: Int=>ByteBuffer) = new FloatTrackRecord(createBuffer(size),size,recOffset)
  def apply(recOffset: Int, nRecords: Int) = {
    new FloatTrackRecord(ByteBuffer.allocate(recOffset + (nRecords*size)).order(ByteOrder.nativeOrder), size,recOffset)
  }
}

class FloatTrackRecord (bbuf: ByteBuffer, recSize: Int, recOffset: Int) extends BufferRecord (recSize,bbuf,recOffset) {

  def this (bbuf: ByteBuffer) = this(bbuf,FloatTrackRecord.size,0)
  def this (bbuf: ByteBuffer, recOff: Int) = this(bbuf,FloatTrackRecord.size,recOff)

  val id   = char(0, 8)    // id (max 8 char) - channel specific (usually externally assigned track number)
  val cs   = char(8, 8)    // call sign (max 8 char) - might be assigned later
  val date = long(16)      // epoch in millis
  val stat = int(24)       // status bit field

  val lat  = float(28)     // degrees
  val lon  = float(32)     // degrees
  val alt  = float(36)     // meters

  val hdg  = float(40)     // heading in degrees
  val spd  = float(44)     // ground speed in m/s
  val vr   = float(48)     // vertical rate in m/s
}


object LockableFloatTrackRecord {
  final val size = FloatTrackRecord.size + 4
}

class LockableFloatTrackRecord (bbuf: ByteBuffer, recOffset: Int)
                        extends FloatTrackRecord(bbuf,LockableFloatTrackRecord.size, recOffset) with LockableRecord {
  protected val lock = int(FloatTrackRecord.size)     // add lock field
}