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

import java.nio.{ByteBuffer, ByteOrder}

import gov.nasa.race._
import gov.nasa.race.cl.CLUtils._
import gov.nasa.race.common.BufferRecord
import gov.nasa.race.util.StringUtils
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.{CL11, CLContextCallbackI}
import org.lwjgl.system.MemoryUtil

import scala.reflect.ClassTag

object CLContext {

  private def _allocContext (devices: CLDevice*): Long = withMemoryStack { stack =>
    val err = stack.allocInt

    // at least on OS X we can't do a "val ctxProps = stack.allocPointerBuffer(0)" as this will
    // always fail with CL_INVALID_VALUE - we have to explicitly set the CONTEXT_PLATFORM
    val ctxProps = stack.allocPointerBuffer(CL_CONTEXT_PLATFORM, devices.head.platform.id, 0)

    val ctxDevs = stack.allocPointerBuffer(devices)(_.id)

    val errCb = new CLContextCallbackI {
      override def invoke(err_info: Long, private_info: Long, cb: Long, user_data: Long): Unit = {
        val name = StringUtils.mkString(devices, "[", ",", "]") {
          _.name
        }
        System.err.println(s"device context error in $name: ${MemoryUtil.memUTF8(err_info)}")
      }
    }

    val ctxId = clCreateContext(ctxProps, ctxDevs, errCb, 0, err)
    checkCLError(err)
    ctxId
  }

  def createContext (devices: CLDevice*): CLContext = {
    new CLContext(_allocContext(devices:_*),Array(devices:_*))
  }
  def createSingleDeviceContext (device: CLDevice): CLSingleDeviceContext = new CLSingleDeviceContext(_allocContext(device),device)
}


/**
  * wrapper for OpenCL context object
  *
  * the various create.. methods are just syntactic sugar to reflect the OpenCL related ownership
  *
  * note that OpenCL only allows to put devices of the same platform into the same context, hence we
  * keep context objects in our CLPlatform objects
  *
  * note also that we don't support a context with devices of different byte order
  *
  * NOTE - we don't do resource tracking here. If the client does not release objects itself, created objects have to be
  * explicitly added to a CloseableStack by the caller
  */
class CLContext (val id: Long, val devices: Array[CLDevice]) extends CLResource {

  val platform = getHomogeneous[CLDevice,CLPlatform](devices, _.platform, _.id == _.id).getOrElse {
    throw new RuntimeException(s"heterogenous platform devices not supported for CLContext")
  }

  val byteOrder = getHomogeneous[CLDevice,ByteOrder](devices, _.byteOrder, _ eq _).getOrElse {
    throw new RuntimeException(s"heterogenous byte order not supported for CLContext")
  }

  @inline def includesDevice (device: CLDevice): Boolean = devices.find(_.id == device.id).isDefined

  override def release = clReleaseContext(id).?

  //--- programs

  def createProgram(src: String): CLProgram = CLProgram.createProgram(this,src)

  //--- buffers

  // note that ByteBuffer instances have to be direct for non-blocking enqueue ops
  def createByteRBuffer(data: ByteBuffer): CLByteRBuffer = CLByteBuffer.createByteRBuffer(this,data)
  def createByteWBuffer(data: ByteBuffer): CLByteWBuffer = CLByteBuffer.createByteWBuffer(this,data)
  def createByteRWBuffer(data: ByteBuffer): CLByteRWBuffer = CLByteBuffer.createByteRWBuffer(this,data)

  def createByteRBuffer(capacity: Int):  CLByteRBuffer  = CLByteBuffer.createByteRBuffer(this,capacity)
  def createByteWBuffer(capacity: Int):  CLByteWBuffer  = CLByteBuffer.createByteWBuffer(this,capacity)
  def createByteRWBuffer(capacity: Int): CLByteRWBuffer = CLByteBuffer.createByteRWBuffer(this,capacity)

  def createShortRBuffer(capacity: Int):  CLShortRBuffer  = CLByteBuffer.createShortRBuffer(this,capacity)
  def createShortWBuffer(capacity: Int):  CLShortWBuffer  = CLByteBuffer.createShortWBuffer(this,capacity)
  def createShortRWBuffer(capacity: Int): CLShortRWBuffer = CLByteBuffer.createShortRWBuffer(this,capacity)

  def createIntRBuffer(capacity: Int):  CLIntRBuffer  = CLByteBuffer.createIntRBuffer(this,capacity)
  def createIntWBuffer(capacity: Int):  CLIntWBuffer  = CLByteBuffer.createIntWBuffer(this,capacity)
  def createIntRWBuffer(capacity: Int): CLIntRWBuffer = CLByteBuffer.createIntRWBuffer(this,capacity)

  def createFloatRBuffer(capacity: Int):  CLFloatRBuffer  = CLByteBuffer.createFloatRBuffer(this,capacity)
  def createFloatWBuffer(capacity: Int):  CLFloatWBuffer  = CLByteBuffer.createFloatWBuffer(this,capacity)
  def createFloatRWBuffer(capacity: Int): CLFloatRWBuffer = CLByteBuffer.createFloatRWBuffer(this,capacity)

  def createLongRBuffer(capacity: Int):  CLLongRBuffer  = CLByteBuffer.createLongRBuffer(this,capacity)
  def createLongWBuffer(capacity: Int):  CLLongWBuffer  = CLByteBuffer.createLongWBuffer(this,capacity)
  def createLongRWBuffer(capacity: Int): CLLongRWBuffer = CLByteBuffer.createLongRWBuffer(this,capacity)

  def createDoubleRBuffer(capacity: Int):  CLDoubleRBuffer  = CLByteBuffer.createDoubleRBuffer(this,capacity)
  def createDoubleWBuffer(capacity: Int):  CLDoubleWBuffer  = CLByteBuffer.createDoubleWBuffer(this,capacity)
  def createDoubleRWBuffer(capacity: Int): CLDoubleRWBuffer = CLByteBuffer.createDoubleRWBuffer(this,capacity)


  def createArrayRBuffer[T<:AnyVal :ClassTag](data: Array[T]): CLArrayRBuffer[T] = CLArrayBuffer.createArrayRBuffer(this,data)
  def createArrayRWBuffer[T<:AnyVal :ClassTag](data: Array[T]): CLArrayRWBuffer[T] = CLArrayBuffer.createArrayRWBuffer(this,data)
  // no point having a WBuffer ctor with a raw data argument since there would be nothing to write on the host side

  def createArrayWBuffer[T<:AnyVal :ClassTag](length: Int): CLArrayWBuffer[T] = CLArrayBuffer.createArrayWBuffer(this,length)
  def createArrayRBuffer[T<:AnyVal :ClassTag](length: Int): CLArrayRBuffer[T] = CLArrayBuffer.createArrayRBuffer(this,length)
  def createArrayRWBuffer[T<:AnyVal :ClassTag](length: Int): CLArrayRWBuffer[T] = CLArrayBuffer.createArrayRWBuffer(this,length)

  def createRecordRBuffer[R <: BufferRecord](length: Int, createRecord: (ByteBuffer)=>R): CLRecordRBuffer[R] = CLRecordBuffer.createRecordRBuffer(this,length,createRecord)
  def createRecordWBuffer[R <: BufferRecord](length: Int, createRecord: (ByteBuffer)=>R): CLRecordWBuffer[R] = CLRecordBuffer.createRecordWBuffer(this,length,createRecord)
  def createRecordRWBuffer[R <: BufferRecord](length: Int, createRecord: (ByteBuffer)=>R): CLRecordRWBuffer[R] = CLRecordBuffer.createRecordRWBuffer(this,length,createRecord)

  // we keep mapped buffers RW since we couldn't enforce access restriction through the type system
  def createMappedByteBuffer(size: Long): CLMappedByteBuffer = CLMappedBuffer.createMappedByteBuffer(this,size)
  def createMappedRecordBuffer[R <: BufferRecord](length: Int, createRecord: (ByteBuffer)=>R): CLMappedRecordBuffer[R] = CLMappedBuffer.createMappedRecordBuffer(this,length,createRecord)

  //--- events

  def createUserEvent: CLEvent = withMemoryStack { stack =>
    val err = stack.allocInt
    val eid = CL11.clCreateUserEvent(id,err)
    checkCLError(err)
    new CLEvent(eid,this)
  }
}

/**
  * the special case if we have only a single device and hence know how to create a command queue
  */
class CLSingleDeviceContext(id: Long, val device: CLDevice) extends CLContext(id,Array[CLDevice](device)) {

  def createCommandQueue (outOfOrder: Boolean=false): CLCommandQueue = CLCommandQueue.createCommandQueue(this,device,outOfOrder)

  def createAndBuildProgram (src: String): CLProgram = {
    val prog = createProgram(src)
    device.buildProgram(prog)
    prog
  }
}
