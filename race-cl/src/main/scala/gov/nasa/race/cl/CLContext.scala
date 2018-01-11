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

import gov.nasa.race.cl.CLUtils._
import gov.nasa.race.common.{BufferRecord, CloseStack}
import gov.nasa.race.util.StringUtils
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.{CL11, CLContextCallbackI}
import org.lwjgl.system.MemoryUtil

import scala.reflect.ClassTag

object CLContext {

  private def _allocContext (devices: CLDevice*): Long = withMemoryStack { stack =>
    val err = stack.allocInt
    val ctxProps = stack.allocPointerBuffer(0)
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

  def createContext (devices: CLDevice*): CLContext = new CLContext(_allocContext(devices:_*),Array(devices:_*))
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
  * NOTE - we don't do resource tracking here. If the client does not release objects itself, created objects have to be
  * explicitly added to a CloseableStack by the caller
  */
class CLContext (val id: Long, val devices: Array[CLDevice]) extends CLResource {

  @inline def includesDevice (device: CLDevice): Boolean = devices.find(_.id == device.id).isDefined

  override def release = clReleaseContext(id).?

  //--- programs

  def createProgram(src: String): CLProgram = CLProgram.createProgram(this,src)

  //--- buffers

  def createArrayRBuffer[T<:AnyVal :ClassTag](data: Array[T]): CLArrayRBuffer[T] = CLArrayBuffer.createArrayRBuffer(this,data)
  def createArrayRWBuffer[T<:AnyVal :ClassTag](data: Array[T]): CLArrayRWBuffer[T] = CLArrayBuffer.createArrayRWBuffer(this,data)

  def createArrayWBuffer[T<:AnyVal :ClassTag](length: Int): CLArrayWBuffer[T] = CLArrayBuffer.createArrayWBuffer(this,length)
  def createArrayRBuffer[T<:AnyVal :ClassTag](length: Int): CLArrayRBuffer[T] = CLArrayBuffer.createArrayRBuffer(this,length)
  def createArrayRWBuffer[T<:AnyVal :ClassTag](length: Int): CLArrayRWBuffer[T] = CLArrayBuffer.createArrayRWBuffer(this,length)

  def createMappedByteBuffer(size: Long): CLMappedByteBuffer = CLMappedBuffer.createMappedByteBuffer(this,size)
  def createMappedRecordBuffer[R <: BufferRecord](length: Int, createRecord: (ByteBuffer)=>R): CLMappedRecordBuffer[R] = {
    CLMappedBuffer.createMappedRecordBuffer(this,length,createRecord)
  }

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
