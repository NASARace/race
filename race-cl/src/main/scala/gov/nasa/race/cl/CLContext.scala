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
import gov.nasa.race._
import gov.nasa.race.common.{BufferRecord, CloseStack}
import gov.nasa.race.util.StringUtils
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.{CL11, CLContextCallbackI, CLEventCallbackI}
import org.lwjgl.system.MemoryUtil

object CLContext {

  def apply (devices: CLDevice*)
            (implicit resources: CloseStack): CLContext = withMemoryStack { stack =>
    val err = stack.allocInt
    val ctxProps = stack.allocPointerBuffer(0)
    val ctxDevs = stack.allocPointerBuffer(devices)(_.id)

    val errCb = new CLContextCallbackI {
      override def invoke(err_info: Long, private_info: Long, cb: Long, user_data: Long): Unit = {
        val name = StringUtils.mkString(devices,"[",",","]"){ _.name}
        System.err.println(s"device context error in $name: ${MemoryUtil.memUTF8(err_info)}")
      }
    }

    val ctxId = clCreateContext(ctxProps,ctxDevs,errCb,0,err)
    checkCLError(err)
    resources.add( new CLContext(ctxId,Array(devices:_*)) )
  }

}


/**
  * wrapper for OpenCL context object
  *
  * note that OpenCL only allows to put devices of the same platform into the same context, hence we
  * keep context objects in our CLPlatform objects
  */
class CLContext (val id: Long, val devices: Array[CLDevice]) extends AutoCloseable {

  @inline def includesDevice (device: CLDevice): Boolean = devices.find(_.id == device.id).isDefined

  override def close = clReleaseContext(id).?

  //--- programs

  def createProgram (src: String)
                    (implicit resources: CloseStack): CLProgram = withMemoryStack { stack =>
    val err = stack.allocInt
    val pid = clCreateProgramWithSource(id,src,err)
    checkCLError(err)
    resources.add( new CLProgram(pid,this) )
  }

  //--- buffers

  def createIntArrayWBuffer (data: Array[Int])
                             (implicit res: CloseStack): IntArrayWBuffer = res.add( CLBuffer.createIntArrayW(data,this) )
  def createIntArrayWBuffer (length: Int)
                             (implicit res: CloseStack): IntArrayWBuffer = res.add( CLBuffer.createIntArrayW(length,this) )

  def createIntArrayRBuffer(data: Array[Int])
                           (implicit res: CloseStack): IntArrayRBuffer = res.add( CLBuffer.createIntArrayR(data,this) )
  def createIntArrayRBuffer(length: Int)
                           (implicit res: CloseStack): IntArrayRBuffer = res.add( CLBuffer.createIntArrayR(length,this) )

  def createIntArrayRWBuffer(data: Array[Int])
                            (implicit res: CloseStack): IntArrayRWBuffer = res.add( CLBuffer.createIntArrayRW(data,this) )
  def createIntArrayRWBuffer(length: Int)
                            (implicit res: CloseStack): IntArrayRWBuffer = res.add( CLBuffer.createIntArrayRW(length,this) )

  def createMappedByteBuffer (size: Long)
                              (implicit res: CloseStack): MappedByteBuffer = res.add( CLBuffer.createMappedByteBuffer(size,this) )
  def createMappedRecordBuffer[R <: BufferRecord] (length: Int, createRecord: (ByteBuffer)=>R)
                                                  (implicit res: CloseStack): MappedRecordBuffer[R] = {
    res.add( CLBuffer.createMappedRecordBuffer(length,createRecord,this))
  }

  //--- events

  def createUserEvent: CLEvent = withMemoryStack { stack =>
    val err = stack.allocInt
    val eid = CL11.clCreateUserEvent(id,err)
    checkCLError(err)
    new CLEvent(eid,this)
  }
}
