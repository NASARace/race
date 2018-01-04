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
package gov.nasa.race.common

import java.nio.ByteBuffer

/**
  * accessor for java.nio.ByteBuffers that hold an array of 'records' with scalar fields for which we need to
  * be able to control/specify alignment
  *
  * we keep the recordOffset variable but compute it from the checked recordIndex so that we can slide
  * the BufferRecord over the whole data buffer
  *
  * note this automatically adjusts to the endian-ness of the underlying buffer
  *
  * use concrete records to define fields like this:
  * {{{
  *   class MyRecord (buffer: ByteBuffer) extends BufferRecord(buffer,8) {
  *     // read-write field 'a'
  *     def a = getInt(0)
  *     def a_= (v: Int) = putInt(0,v)
  *
  *     // read-only field 'b'
  *     def b = getDouble(4)
  *   }
  * }}}
  *
  * while the requirement to explicitly define field getters and setters seems to add some boiler plate, we
  * actually want to be able to control which fields are read/write since a primary use case is to define the
  * interface structures for native code such as OpenCL kernel arguments, which would otherwise not be
  * supported by more abstract (and expensive) approaches such as scodec codecs
  */
abstract class BufferRecord (val buffer: ByteBuffer, val size: Int) {
  protected var recordOffset: Int = 0

  def setRecordIndex (idx: Int) = {
    val max = (buffer.capacity / size) - 1
    val i = idx * size
    if (i < 0 || i > max) throw new RuntimeException(s"record index out of bounds: $idx (0..$max)")
    recordOffset = i
  }

  def getByte (fieldOffset: Int): Byte = buffer.get(recordOffset + fieldOffset)
  def putByte (fieldOffset: Int, v: Byte): BufferRecord = { buffer.put(recordOffset + fieldOffset, v); this }

  def getShort (fieldOffset: Short): Short = buffer.getShort(recordOffset + fieldOffset)
  def putShort (fieldOffset: Int, v: Short): BufferRecord = { buffer.putShort(recordOffset + fieldOffset, v); this }

  def getInt (fieldOffset: Int): Int = buffer.getInt(recordOffset + fieldOffset)
  def putInt (fieldOffset: Int, v: Int): BufferRecord = { buffer.putInt(recordOffset + fieldOffset, v); this }

  def getLong (fieldOffset: Int): Long = buffer.getLong(recordOffset + fieldOffset)
  def putLong (fieldOffset: Int, v: Long): BufferRecord = { buffer.putLong(recordOffset + fieldOffset, v); this }

  def getFloat (fieldOffset: Int): Float = buffer.getFloat(recordOffset + fieldOffset)
  def putFloat (fieldOffset: Int, v: Float): BufferRecord = { buffer.putFloat(recordOffset + fieldOffset, v); this }

  def getDouble (fieldOffset: Int): Double = buffer.getDouble(recordOffset + fieldOffset)
  def putDouble (fieldOffset: Int, v: Double): BufferRecord = { buffer.putDouble(recordOffset + fieldOffset, v); this }

  // ..maybe we should add vector types (double3 and the like)
}
