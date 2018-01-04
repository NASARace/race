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

import CLUtils._
import gov.nasa.race.common.CloseStack
import gov.nasa.race.util.StringUtils
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.CLContextCallbackI
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

  def createProgram (src: String)
                    (implicit resources: CloseStack): CLProgram = withMemoryStack { stack =>
    val err = stack.allocInt
    val pid = clCreateProgramWithSource(id,src,err)
    checkCLError(err)
    resources.add( new CLProgram(pid,this) )
  }

  def createIntArrayCWBuffer (data: Array[Int])
                             (implicit res: CloseStack): IntArrayCWBuffer = res.add( CLBuffer.createIntArrayCW(data,this) )
  def createIntArrayCWBuffer (length: Int)
                             (implicit res: CloseStack): IntArrayCWBuffer = res.add( CLBuffer.createIntArrayCW(length,this) )

  def createIntArrayCRBuffer (data: Array[Int])
                             (implicit res: CloseStack): IntArrayCRBuffer = res.add( CLBuffer.createIntArrayCR(data,this) )
  def createIntArrayCRBuffer (length: Int)
                             (implicit res: CloseStack): IntArrayCRBuffer = res.add( CLBuffer.createIntArrayCR(length,this) )

  def createIntArrayCRWBuffer (data: Array[Int])
                              (implicit res: CloseStack): IntArrayCRWBuffer = res.add( CLBuffer.createIntArrayCRW(data,this) )
  def createIntArrayCRWBuffer (length: Int)
                              (implicit res: CloseStack): IntArrayCRWBuffer = res.add( CLBuffer.createIntArrayCRW(length,this) )

  def createMappedRecordBuffer (rec: MappedRecord)
                              (implicit res: CloseStack): MappedRecordBuffer = res.add( CLBuffer.createMappedRecordBuffer(rec,this) )
}
