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

import java.nio.ByteBuffer

import CLUtils._
import org.lwjgl.PointerBuffer
import org.lwjgl.opencl.CL10._

import scala.reflect.{ClassTag, classTag}

/**
  * CLBuffers that are backed by arrays
  * there are two lines of specialization: element type (Short,Int,Float,Double) and buffer access (R,W,RW)
  *
  * Unfortunately we have to either create plenty of concrete classes (IntR, IntW, IntRW, FloatR, FloatW, FloatRW,...)
  * or resort to some runtime type system use. Since the native costs of enqueue calls is much higher anyways we do
  * the latter
  *
  * note that read/write is from the device perspective, i.e. corresponding host operations are reverse
  *
  * THIS CLASS ONLY PROVIDES BLOCKING READ/WRITE ENQUEUE OPERATIONS.
  * Array backed buffers are not pinned in memory (only direct ByteBuffers are), hence we cannot enqueue non-blocking
  * operations (LWJGL now throws a RuntimeException if we try) since the JVM might move the array object while OpenCL
  * is executing the non-blocked op.
  */

object CLArrayBuffer {
  final val ClassOfByte: Class[_] = classOf[Byte]
  final val ClassOfShort: Class[_] = classOf[Short]
  final val ClassOfInt: Class[_] = classOf[Int]
  final val ClassOfLong: Class[_] = classOf[Long]
  final val ClassOfFloat: Class[_] = classOf[Float]
  final val ClassOfDouble: Class[_] = classOf[Double]

  def elementSize[T<:AnyVal :ClassTag]: Int = classTag[T].runtimeClass match {
    case ClassOfByte => 1
    case ClassOfShort => 2
    case ClassOfInt => 4
    case ClassOfLong => 8
    case ClassOfFloat => 4
    case ClassOfDouble => 8
  }

  //--- the low level creators (no registration here, has to happen in the caller)

  def createBuffer[T<:AnyVal :ClassTag] (context: CLContext, length: Int, flags: Long): Long = withMemoryStack { stack =>
    val err = stack.allocInt
    val bid = clCreateBuffer(context.id,flags, elementSize[T] * length,err)
    checkCLError(err)
    bid
  }
  def createBuffer[T<:AnyVal :ClassTag] (context: CLContext, data: Array[T], flags: Long): Long = {
    val err = new Array[Int](1)
    val bid = classTag[T].runtimeClass match {
      case CLArrayBuffer.ClassOfByte => clCreateBuffer(context.id,flags,ByteBuffer.wrap(data.asInstanceOf[Array[Byte]]),err)
      case CLArrayBuffer.ClassOfShort => clCreateBuffer(context.id,flags,data.asInstanceOf[Array[Short]],err)
      case CLArrayBuffer.ClassOfInt => clCreateBuffer(context.id,flags,data.asInstanceOf[Array[Int]],err)
      case CLArrayBuffer.ClassOfFloat => clCreateBuffer(context.id,flags,data.asInstanceOf[Array[Float]],err)
      case CLArrayBuffer.ClassOfDouble => clCreateBuffer(context.id,flags,data.asInstanceOf[Array[Double]],err)
    }
    checkCLError(err(0))
    bid
  }

  def createArrayWBuffer[T<:AnyVal :ClassTag] (context: CLContext, length: Int): CLArrayWBuffer[T] = {
    val data = new Array[T](length)
    val bid = createBuffer[T](context,length, CL_MEM_WRITE_ONLY)
    new CLArrayWBuffer[T](bid,data,context)
  }
  def createArrayRBuffer[T<:AnyVal :ClassTag] (context: CLContext, length: Int): CLArrayRBuffer[T] = {
    val data = new Array[T](length)
    val bid = createBuffer[T](context,length, CL_MEM_READ_ONLY)
    new CLArrayRBuffer[T](bid,data,context)
  }
  def createArrayRWBuffer[T<:AnyVal :ClassTag] (context: CLContext, length: Int): CLArrayRWBuffer[T] = {
    val data = new Array[T](length)
    val bid = createBuffer[T](context,length, CL_MEM_READ_WRITE)
    new CLArrayRWBuffer[T](bid,data,context)
  }

  def createArrayRBuffer[T<:AnyVal :ClassTag] (context: CLContext, data: Array[T]): CLArrayRBuffer[T] = {
    val bid = createBuffer(context,data, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR )
    new CLArrayRBuffer[T](bid,data,context)
  }
  def createArrayRWBuffer[T<:AnyVal :ClassTag] (context: CLContext, data: Array[T]): CLArrayRWBuffer[T] = {
    val bid = createBuffer(context,data, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR )
    new CLArrayRWBuffer[T](bid,data,context)
  }
  // it doesn't make sense to have a ArrayWBuffer with CL_MEM_COPY_HOST_PTR since it's only written by the device
}
import CLArrayBuffer._

trait ABuf[T] extends CLBuffer {
  val data: Array[T]
  val elementClass: Class[_]
}

trait AReadBuf[T] extends  ABuf[T] {
  private def _enqueueWriteByte(q: CLCommandQueue,pe: PointerBuffer): Unit   = clEnqueueWriteBuffer(q.id,id,true,0,ByteBuffer.wrap(data.asInstanceOf[Array[Byte]]),null,pe).?
  private def _enqueueWriteShort(q: CLCommandQueue,pe: PointerBuffer): Unit  = clEnqueueWriteBuffer(q.id,id,true,0,data.asInstanceOf[Array[Short]],null,pe).?
  private def _enqueueWriteInt(q: CLCommandQueue,pe: PointerBuffer): Unit    = clEnqueueWriteBuffer(q.id,id,true,0,data.asInstanceOf[Array[Int]],null,pe).?
  private def _enqueueWriteFloat(q: CLCommandQueue,pe: PointerBuffer): Unit  = clEnqueueWriteBuffer(q.id,id,true,0,data.asInstanceOf[Array[Float]],null,pe).?
  private def _enqueueWriteDouble(q: CLCommandQueue,pe: PointerBuffer): Unit = clEnqueueWriteBuffer(q.id,id,true,0,data.asInstanceOf[Array[Double]],null,pe).?

  val _enqueueWrite: (CLCommandQueue,PointerBuffer)=>Unit = elementClass match {
    case ClassOfByte => _enqueueWriteByte
    case ClassOfShort => _enqueueWriteShort
    case ClassOfInt => _enqueueWriteInt
    case ClassOfFloat => _enqueueWriteFloat
    case ClassOfDouble => _enqueueWriteDouble
  }

  def enqueueBlockingWrite (queue: CLCommandQueue) = _enqueueWrite(queue,null)

  /**
    * note - this is the low level version, the caller is responsible for calling clReleaseEvent on the returned event pointer
    * there is no point having a blocking write since we create a CLEvent to wait on
    */
  def enqueueWriteEv (queue: CLCommandQueue): Long = withMemoryStack { stack =>
    val pev = stack.allocPointer
    _enqueueWrite(queue,pev)
    pev(0)
  }

  def enqueueWriteEvent (queue: CLCommandQueue): CLEvent = new CLEvent(enqueueWriteEv(queue),context)
}

trait AWriteBuf[T] extends  ABuf[T] {
  private def _enqueueReadByte(q: CLCommandQueue): Unit   = clEnqueueReadBuffer(q.id,id,true,0,ByteBuffer.wrap(data.asInstanceOf[Array[Byte]]),null,null).?
  private def _enqueueReadShort(q: CLCommandQueue): Unit  = clEnqueueReadBuffer(q.id,id,true,0,data.asInstanceOf[Array[Short]],null,null).?
  private def _enqueueReadInt(q: CLCommandQueue): Unit    = clEnqueueReadBuffer(q.id,id,true,0,data.asInstanceOf[Array[Int]],null,null).?
  private def _enqueueReadFloat(q: CLCommandQueue): Unit  = clEnqueueReadBuffer(q.id,id,true,0,data.asInstanceOf[Array[Float]],null,null).?
  private def _enqueueReadDouble(q: CLCommandQueue): Unit = clEnqueueReadBuffer(q.id,id,true,0,data.asInstanceOf[Array[Double]],null,null).?

  private val _enqueueRead: (CLCommandQueue)=>Unit = elementClass match {
    case ClassOfByte => _enqueueReadByte
    case ClassOfShort => _enqueueReadShort
    case ClassOfInt => _enqueueReadInt
    case ClassOfFloat => _enqueueReadFloat
    case ClassOfDouble => _enqueueReadDouble
  }
  def enqueueBlockingRead(queue: CLCommandQueue) = _enqueueRead(queue)

}

abstract class CLArrayBuffer [T<:AnyVal :ClassTag] extends CLBuffer {
  val data: Array[T]

  val elementClass: Class[_] = classTag[T].runtimeClass
  val size: Long = elementSize[T] * data.length
  val length: Int = data.length
}

class CLArrayWBuffer [T<:AnyVal :ClassTag](val id: Long, val data: Array[T], val context: CLContext)
                                                                      extends CLArrayBuffer[T] with AWriteBuf[T]
class CLArrayRBuffer [T<:AnyVal :ClassTag](val id: Long, val data: Array[T], val context: CLContext)
                                                                      extends CLArrayBuffer[T] with AReadBuf[T]
class CLArrayRWBuffer [T<:AnyVal :ClassTag](val id: Long, val data: Array[T], val context: CLContext)
                                                                      extends CLArrayBuffer[T] with AReadBuf[T] with AWriteBuf[T]
