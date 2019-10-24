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

import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.util

import gov.nasa.race.util.{StringUtils, UnsafeUtils}

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

/**
  * accessor for java.nio.ByteBuffers that hold an array of 'records' with scalar fields for which we need to
  * be able to control/specify alignment. This is used as a analogy to C 'struct' (and 'union') definitions with
  * explicit control over alignment
  *
  * we keep the recordOffset variable but compute it from the checked recordIndex so that we can slide
  * the BufferRecord over the whole data buffer
  *
  * note that we do allow a null buffer argument so that we can compute buffer sizes from the record number and type.
  * Alternatively we support creating the buffer via a supplied client function that takes the record size as a
  * single argument
  */

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

/**
  * field based accessor for ByteBuffers
  *
  * note that we keep the ByteBuffer instance invariant so that we don't have to check boundaries
  * on each field access. The downside is that buffer allocation that depends on the record size
  * becomes more difficult since we can't use the record instance to obtain it
  *
  * @param size of the record in bytes
  * @param buffer we read from/write to
  * @param recStart buffer offset where record data starts
  */
abstract class BufferRecord (val size: Int, val buffer: ByteBuffer, val recStart: Int = 0) {
  import BufferRecord._

  def this (size: Int, createBuffer: (Int)=>ByteBuffer, recStart: Int)  = this(size,createBuffer(size),recStart)

  protected var maxRecordIndex = getMaxRecordIndex
  protected var recordOffset: Int = recStart
  protected var recordIndex: Int = 0

  protected var fields: Array[Field] = null // on demand, but since it is static we keep it once computed

  protected def getMaxRecordIndex = if (buffer != null) ((buffer.capacity - recStart)/size)-1 else -1


  def setRecordIndex (idx: Int): Unit = {
    if (idx < 0 || idx > maxRecordIndex) throw new RuntimeException(s"record index out of bounds: $idx (0..$maxRecordIndex)")
    recordIndex = idx
    recordOffset = recStart + idx * size
  }

  def index = recordIndex

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

  def foreachRecord (f: (this.type) =>Unit): Unit = {
    for (i <- 0 to maxRecordIndex) {
      setRecordIndex(i)
      f(this)
    }
  }

  def clear: Unit = {
    for ( i <- recordOffset until recordOffset + size) buffer.put(i,0.toByte) // it sucks there is no memset for buffers, and that Scala 2.13.1 has a problem resolving ByteBuffer methods
  }

  def copyTo (idx: Int): Unit = {
    /**
    var i = recordOffset
    val i1 = i + size
    var j = recStart + idx*size
    while (i < i1){
      buffer.put(j, buffer.get(i))
      i += 1
      j += 1
    }
      **/
    val src = buffer.duplicate  // very convoluted way of saying memcpy
    src.position(recordOffset)  // NOTE - contrary to what javadoc says the position is at least for DirectByteBuffers NOT copied
    src.limit(recordOffset + size)
    buffer.position(recStart + idx*size)
    buffer.put(src)
  }
  def moveTo (idx: Int): Unit = {
    copyTo(idx)
    clear
  }

  // TODO - this does not support inheritance, but not sure if we should support (C struct/union analogy)
  def getFields: Array[Field] = {
    if (fields == null) {
      fields = getClass.getDeclaredFields.filter(f => classOf[RecordField].isAssignableFrom(f.getType))
    }
    fields
  }

  override def toString = {
    val sb = new StringBuilder(s"${getClass.getName}{")
    sb.append("idx:")
    sb.append(recordIndex)
    getFields.foreach { f=>
      f.setAccessible(true)
      sb.append(',')
      sb.append(f.getName)
      sb.append(':')
      val o = f.get(this).asInstanceOf[RecordField]
      sb.append(o.valueToString)
    }
    sb.append('}')
    sb.toString
  }


  //--- the field types

  trait RecordField {
    val fieldOffset: Int
    def valueToString: String
  }

  class CharArrayField (val fieldOffset: Int, val length: Int) extends RecordField {
    def := (v: String): Unit = StringUtils.putCString(v,buffer,recordOffset+fieldOffset,length)

    override def toString = StringUtils.getCString(buffer,recordOffset + fieldOffset,length)
    def valueToString = toString
  }
  def char (fieldOffset: Int, length: Int) = new CharArrayField(fieldOffset,length)

  // we use a packed byte as the underlying storage type since this is the smallest ByteBuffer unit
  class BooleanField (val fieldOffset: Int) extends RecordField with BooleanConvertible {
    def := (v: Boolean): Unit = buffer.put(recordOffset + fieldOffset, if (v) 1.toByte else 0.toByte) // Scala 2.13.1 / JDK 13.0.1 problem resolving ByteBuffer methods
    def toBoolean = buffer.get(recordOffset + fieldOffset) == 1
    def valueToString = toBoolean.toString
  }
  def boolean (fieldOffset: Int) = new BooleanField(fieldOffset)

  class ByteField (val fieldOffset: Int) extends RecordField with ByteConvertible {
    def := (v: Byte): Unit = buffer.put(recordOffset + fieldOffset, v)
    def := (v: Int): Unit = buffer.put(recordOffset + fieldOffset, v.toByte)
    def toByte = buffer.get(recordOffset + fieldOffset)
    def valueToString = toByte.toString
  }
  def byte (fieldOffset: Int) = new ByteField(fieldOffset)

  class ShortField (val fieldOffset: Int) extends RecordField with ShortConvertible {
    def := (v: Short): Unit = buffer.putShort(recordOffset + fieldOffset, v)
    def toShort = buffer.getShort(recordOffset + fieldOffset)
    def valueToString = toShort.toString
  }
  def short (fieldOffset: Int) = new ShortField(fieldOffset)

  class IntField (val fieldOffset: Int) extends RecordField with IntConvertible {
    def := (v: Int): Unit = buffer.putInt(recordOffset + fieldOffset, v)
    def toInt = buffer.getInt(recordOffset + fieldOffset)
    def valueToString = toInt.toString
  }
  def int (fieldOffset: Int) = new IntField(fieldOffset)

  class LongField (val fieldOffset: Int) extends RecordField with LongConvertible {
    def := (v: Long): Unit = buffer.putLong(recordOffset + fieldOffset, v)
    def toLong = buffer.getLong(recordOffset + fieldOffset)
    def valueToString = toLong.toString
  }
  def long (fieldOffset: Int) = new LongField(fieldOffset)

  class FloatField (val fieldOffset: Int) extends RecordField with FloatConvertible {
    def := (v: Float): Unit = buffer.putFloat(recordOffset + fieldOffset, v)
    def := (v: Int): Unit = buffer.putFloat(recordOffset + fieldOffset, v.toFloat)
    def toFloat = buffer.getFloat(recordOffset + fieldOffset)
    def valueToString = toFloat.toString
  }
  def float (fieldOffset: Int) = new FloatField(fieldOffset)

  class DoubleField (val fieldOffset: Int) extends RecordField with DoubleConvertible {
    def := (v: Double): Unit = buffer.putDouble(recordOffset + fieldOffset, v)
    def := (v: Int): Unit = buffer.putDouble(recordOffset + fieldOffset, v.toDouble)
    def toDouble = buffer.getDouble(recordOffset + fieldOffset)
    def valueToString = toDouble.toString
  }
  def double (fieldOffset: Int) = new DoubleField(fieldOffset)
}


trait LockableRecord {
  val buffer: ByteBuffer
  protected var recordOffset: Int

  protected val lock: BufferRecord#IntField // has to be defined in concrete class

  private val acc = UnsafeUtils.getAccessor(buffer)

  /**
    * try nTimes to acquire the record lock, waiting for delay in between attempts
    * NOTE - the protected function f should execute in short,bounded time since this is a semi-busy wait
    * NOTE - the lock is not reentrant
    */
  @tailrec final def tryLockedFor (nTimes: Int, delay: FiniteDuration)(f: =>Unit): Boolean = {
    val addr = recordOffset + lock.fieldOffset
    if (acc.compareAndSwapInt(addr, 0, 1)) {  // this is atomic
      f
      acc.putIntVolatile(addr,0)
      true

    } else {
      if (nTimes > 0) {
        Thread.sleep(delay.toMillis)  // alternatively we could just loop
        tryLockedFor(nTimes-1, delay)(f)
      } else {
        false
      }
    }
  }
}

