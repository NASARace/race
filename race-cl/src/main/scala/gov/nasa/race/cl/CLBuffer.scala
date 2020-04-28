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
package gov.nasa.race.cl

import java.nio.{ByteBuffer, IntBuffer}

import gov.nasa.race.cl.CLUtils._
import org.lwjgl.opencl.CL10._


/**
  * basis for various wrapper types for OpenCL buffer objects
  *
  * note that we want type safety for:
  *  - content type (array or ByteBuffer)
  *  - buffer access mode (read,write,read-write)
  *  - initialization (copy, map)
  */

trait CLBuffer extends CLResource {
  val id: Long
  val size: Long
  val context: CLContext

  override def release = clReleaseMemObject(id).?
}

//--- generic ByteBuffer support

object CLByteBuffer {
  private def _createDirectByteBuf(context: CLContext, capacity: Int): ByteBuffer = {
    val buf = ByteBuffer.allocateDirect(capacity)
    buf.order(context.byteOrder)
    buf
  }

  private def _createBuffer(context: CLContext, size: Long, flags: Int): Long = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = clCreateBuffer(context.id, flags, size, err)
      checkCLError(err)
      bid
    }
  }

  //--- direct read buffers

  def createByteRBuffer (context: CLContext, buf: ByteBuffer): CLByteRBuffer = {
    buf.order(context.byteOrder)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLByteRBuffer(id, buf, context)
  }

  def createByteRBuffer (context: CLContext, capacity: Int): CLByteRBuffer = {
    val buf = _createDirectByteBuf(context, capacity)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLByteRBuffer(id, buf, context)
  }

  def createShortRBuffer (context: CLContext, capacity: Int): CLShortRBuffer = {
    val buf = _createDirectByteBuf(context, capacity*2)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLShortRBuffer(id, buf, context)
  }

  def createIntRBuffer (context: CLContext, capacity: Int): CLIntRBuffer = {
    val buf = _createDirectByteBuf(context, capacity*4)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLIntRBuffer(id, buf, context)
  }

  def createFloatRBuffer (context: CLContext, capacity: Int): CLFloatRBuffer = {
    val buf = _createDirectByteBuf(context, capacity*4)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLFloatRBuffer(id, buf, context)
  }

  def createLongRBuffer (context: CLContext, capacity: Int): CLLongRBuffer = {
    val buf = _createDirectByteBuf(context, capacity*8)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLLongRBuffer(id, buf, context)
  }

  def createDoubleRBuffer (context: CLContext, capacity: Int): CLDoubleRBuffer = {
    val buf = _createDirectByteBuf(context, capacity*8)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLDoubleRBuffer(id, buf, context)
  }

  //--- direct write buffers

  def createByteWBuffer (context: CLContext, buf: ByteBuffer): CLByteWBuffer = {
    buf.order(context.byteOrder)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLByteWBuffer(id, buf, context)
  }

  def createByteWBuffer (context: CLContext, capacity: Int): CLByteWBuffer = {
    val buf = _createDirectByteBuf(context, capacity)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLByteWBuffer(id, buf, context)
  }

  def createShortWBuffer (context: CLContext, capacity: Int): CLShortWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*2)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLShortWBuffer(id, buf, context)
  }

  def createIntWBuffer (context: CLContext, capacity: Int): CLIntWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*4)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLIntWBuffer(id, buf, context)
  }

  def createFloatWBuffer (context: CLContext, capacity: Int): CLFloatWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*4)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLFloatWBuffer(id, buf, context)
  }

  def createLongWBuffer (context: CLContext, capacity: Int): CLLongWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*8)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLLongWBuffer(id, buf, context)
  }

  def createDoubleWBuffer (context: CLContext, capacity: Int): CLDoubleWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*8)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLDoubleWBuffer(id, buf, context)
  }

  //--- direct read/write buffers

  def createByteRWBuffer (context: CLContext, buf: ByteBuffer): CLByteRWBuffer = {
    buf.order(context.byteOrder)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLByteRWBuffer(id, buf, context)
  }

  def createByteRWBuffer (context: CLContext, capacity: Int): CLByteRWBuffer = {
    val buf = _createDirectByteBuf(context, capacity)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLByteRWBuffer(id, buf, context)
  }

  def createShortRWBuffer (context: CLContext, capacity: Int): CLShortRWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*2)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLShortRWBuffer(id, buf, context)
  }

  def createIntRWBuffer (context: CLContext, capacity: Int): CLIntRWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*4)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLIntRWBuffer(id, buf, context)
  }

  def createFloatRWBuffer (context: CLContext, capacity: Int): CLFloatRWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*4)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLFloatRWBuffer(id, buf, context)
  }

  def createLongRWBuffer (context: CLContext, capacity: Int): CLLongRWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*8)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLLongRWBuffer(id, buf, context)
  }

  def createDoubleRWBuffer (context: CLContext, capacity: Int): CLDoubleRWBuffer = {
    val buf = _createDirectByteBuf(context, capacity*8)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLDoubleRWBuffer(id, buf, context)
  }

}


trait CLByteBuffer extends CLBuffer {
  val buf: ByteBuffer
  val size: Long = buf.capacity
  def length: Int = size.toInt

  def clear: Unit = buf.clear
}

trait ByteWBuf extends CLByteBuffer {
  def enqueueRead(queue: CLCommandQueue, isBlocking: Boolean=false): Unit = {
    clEnqueueReadBuffer(queue.id,id,isBlocking,0,buf,null,null).?
  }
  def enqueueBlockingRead(queue: CLCommandQueue) = enqueueRead(queue,true)

}

trait ByteRBuf extends CLByteBuffer {
  def enqueueWrite(queue: CLCommandQueue, isBlocking: Boolean=false): Unit = {
    clEnqueueWriteBuffer(queue.id,id,isBlocking,0,buf,null,null).?
  }
  def enqueueBlockingWrite(queue: CLCommandQueue) = enqueueWrite(queue,true)

  def enqueueWriteEv (queue: CLCommandQueue): Long = withMemoryStack { stack =>
    val pev = stack.allocPointer
    clEnqueueWriteBuffer(queue.id,id,false,0,buf,null,pev).?
    pev(0)
  }

  def enqueueWriteEvent (queue: CLCommandQueue): CLEvent = new CLEvent(enqueueWriteEv(queue),context)
}

//--- traits that add type specific accessors to underlying ByteBuffer

trait CLShortBuffer extends CLByteBuffer {
  def apply (i: Int): Short = buf.getShort(i*2)
  def update (i: Int, v: Short): Unit = buf.putShort(i*2, v)
  override def length: Int = buf.capacity / 2
  def put(a: Array[Short]): Unit = buf.asShortBuffer.put(a,0,a.length)
  def put(a: Array[Short], off: Int, len: Int): Unit = buf.asShortBuffer.put(a,off,len)
}
trait CLIntBuffer extends CLByteBuffer {
  def apply (i: Int): Int = buf.getInt(i*4)
  def update (i: Int, v: Int): Unit = buf.putInt(i*4, v)
  override def length: Int = buf.capacity / 4
  def put(a: Array[Int]): Unit = buf.asIntBuffer.put(a,0,a.length)
  def put(a: Array[Int], off: Int, len: Int): Unit = buf.asIntBuffer.put(a,off,len)
}
trait CLFloatBuffer extends CLByteBuffer {
  def apply (i: Int): Float = buf.getFloat(i*4)
  def update (i: Int, v: Int): Unit = buf.putFloat(i*4, v.toFloat)
  override def length: Int = buf.capacity / 4
  def put(a: Array[Float]): Unit = buf.asFloatBuffer.put(a,0,a.length)
  def put(a: Array[Float], off: Int, len: Int): Unit = buf.asFloatBuffer.put(a,off,len)
}
trait CLLongBuffer extends CLByteBuffer {
  def apply (i: Int): Long = buf.getLong(i*8)
  def update (i: Int, v: Long): Unit = buf.putLong(i*8, v)
  override def length: Int = buf.capacity / 8
  def put(a: Array[Long]): Unit = buf.asLongBuffer.put(a,0,a.length)
  def put(a: Array[Long], off: Int, len: Int): Unit = buf.asLongBuffer.put(a,off,len)
}
trait CLDoubleBuffer extends CLByteBuffer {
  def apply (i: Int): Double = buf.getDouble(i*8)
  def update (i: Int, v: Double): Unit = buf.putDouble(i*8, v)
  override def length: Int = buf.capacity / 8
  def put(a: Array[Double]): Unit = buf.asDoubleBuffer.put(a,0,a.length)
  def put(a: Array[Double], off: Int, len: Int): Unit = buf.asDoubleBuffer.put(a,off,len)
}

// note that provided ByteBuffers

class CLByteRBuffer    (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf
class CLShortRBuffer   (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with CLShortBuffer
class CLIntRBuffer     (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with CLIntBuffer
class CLFloatRBuffer   (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with CLFloatBuffer
class CLLongRBuffer    (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with CLLongBuffer
class CLDoubleRBuffer  (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with CLDoubleBuffer

class CLByteWBuffer    (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf
class CLShortWBuffer   (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf with CLShortBuffer
class CLIntWBuffer     (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf with CLIntBuffer
class CLFloatWBuffer   (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf with CLFloatBuffer
class CLLongWBuffer    (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf with CLLongBuffer
class CLDoubleWBuffer  (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf with CLDoubleBuffer

class CLByteRWBuffer   (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf
class CLShortRWBuffer  (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf with CLShortBuffer
class CLIntRWBuffer    (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf with CLIntBuffer
class CLFloatRWBuffer  (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf with CLFloatBuffer
class CLLongRWBuffer   (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf with CLLongBuffer
class CLDoubleRWBuffer (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf with CLDoubleBuffer

