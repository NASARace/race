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
import scala.language.implicitConversions

/**
  * accessor for java.nio.ByteBuffers that hold an array of 'records' with scalar fields for which we need to
  * be able to control/specify alignment
  *
  * we keep the recordOffset variable but compute it from the checked recordIndex so that we can slide
  * the BufferRecord over the whole data buffer
  *
  * note that we do allow a null buffer argument so that we can compute buffer sizes from the record number and type
  */

abstract class BufferRecord (val size: Int, val buffer: ByteBuffer) {
  import BufferRecord._

  protected var maxRecordIndex = getMaxRecordIndex
  protected var recordOffset: Int = 0

  protected def getMaxRecordIndex = if (buffer != null) (buffer.capacity/size)-1 else -1

  def setRecordIndex (idx: Int): Unit = {
    if (idx < 0 || idx > maxRecordIndex) throw new RuntimeException(s"record index out of bounds: $idx (0..$maxRecordIndex)")
    recordOffset = idx * size
  }

  def index = recordOffset / size

  def apply (idx: Int): this.type = {
    setRecordIndex(idx)
    this
  }

  def foreach( f: =>Unit ): Unit= {
    for (i <- 0 to maxRecordIndex) {
      setRecordIndex(i)
      f
    }
  }

  def getSlicedBuffer: ByteBuffer = ByteBuffer.wrap(buffer.array, recordOffset, size)

  //--- the field types

  // we use a packed byte as the underlying storage type since this is the smallest ByteBuffer unit
  class BooleanField (val fieldOffset: Int) extends BooleanConvertible {
    def := (v: Boolean): Unit = buffer.put(recordOffset + fieldOffset, if (v) 1 else 0)
    def toBoolean = buffer.get(recordOffset + fieldOffset) == 1
  }
  def boolean (fieldOffset: Int) = new BooleanField(fieldOffset)

  class ByteField (val fieldOffset: Int) extends ByteConvertible {
    def := (v: Byte): Unit = buffer.put(recordOffset + fieldOffset, v)
    def := (v: Int): Unit = buffer.put(recordOffset + fieldOffset, v.toByte)
    def toByte = buffer.get(recordOffset + fieldOffset)
  }
  def byte (fieldOffset: Int) = new ByteField(fieldOffset)

  class ShortField (val fieldOffset: Int) extends ShortConvertible {
    def := (v: Short): Unit = buffer.putShort(recordOffset + fieldOffset, v)
    def toShort = buffer.getShort(recordOffset + fieldOffset)
  }
  def short (fieldOffset: Int) = new ShortField(fieldOffset)

  class IntField (val fieldOffset: Int) extends IntConvertible {
    def := (v: Int): Unit = buffer.putInt(recordOffset + fieldOffset, v)
    def toInt = buffer.getInt(recordOffset + fieldOffset)
  }
  def int (fieldOffset: Int) = new IntField(fieldOffset)

  class LongField (val fieldOffset: Int) extends LongConvertible {
    def := (v: Long): Unit = buffer.putLong(recordOffset + fieldOffset, v)
    def toLong = buffer.getLong(recordOffset + fieldOffset)
  }
  def long (fieldOffset: Int) = new LongField(fieldOffset)

  class FloatField (val fieldOffset: Int) extends FloatConvertible {
    def := (v: Float): Unit = buffer.putFloat(recordOffset + fieldOffset, v)
    def := (v: Int): Unit = buffer.putFloat(recordOffset + fieldOffset, v.toFloat)
    def toFloat = buffer.getFloat(recordOffset + fieldOffset)
  }
  def float (fieldOffset: Int) = new FloatField(fieldOffset)

  class DoubleField (val fieldOffset: Int) extends DoubleConvertible {
    def := (v: Double): Unit = buffer.putDouble(recordOffset + fieldOffset, v)
    def := (v: Int): Unit = buffer.putDouble(recordOffset + fieldOffset, v.toDouble)
    def toDouble = buffer.getDouble(recordOffset + fieldOffset)
  }
  def double (fieldOffset: Int) = new DoubleField(fieldOffset)
}

object BufferRecord {
  trait DoubleConvertible {
    def toDouble: Double
  }
  implicit def toDouble (o: DoubleConvertible) = o.toDouble

  trait IntConvertible {
    def toInt: Int
  }
  implicit def toInt (o: IntConvertible) = o.toInt

  trait LongConvertible {
    def toLong: Long
  }
  implicit def toLong (o: LongConvertible) = o.toLong

  trait FloatConvertible {
    def toFloat: Float
  }
  implicit def toFloat (o: FloatConvertible) = o.toFloat

  trait ShortConvertible {
    def toShort: Short
  }
  implicit def toShort (o: ShortConvertible) = o.toShort

  trait ByteConvertible {
    def toByte: Byte
  }
  implicit def toByte (o: ByteConvertible) = o.toByte

  trait BooleanConvertible {
    def toBoolean: Boolean
  }
  implicit def toBoolean (o: BooleanConvertible) = o.toBoolean
}
