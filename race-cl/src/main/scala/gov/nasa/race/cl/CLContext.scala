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
import gov.nasa.race.util.StringUtils
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.CLContextCallbackI
import org.lwjgl.system.MemoryUtil

object CLContext {
  def apply (devices: CLDevice*): CLContext = withMemoryStack { stack =>
    val err = stack.allocInt
    val ctxProps = stack.allocPointerBuffer(0)
    val ctxDevs = stack.allocPointerBuffer(devices)(_.id)
    val name = StringUtils.mkString(devices,"[",",","]"){ _.name}
    val errCb = new CLContextCallbackI {
      override def invoke(err_info: Long, private_info: Long, cb: Long, user_data: Long): Unit = {
        System.err.println(s"device context error in $name: ${MemoryUtil.memUTF8(err_info)}")
      }
    }
    val ctxId = clCreateContext(ctxProps,ctxDevs,errCb,0,err)
    checkCLError(err)
    new CLContext(ctxId,Array(devices:_*))
  }
}


/**
  * wrapper for OpenCL context object
  *
  * note that OpenCL only allows to put devices of the same platform into the same context, hence we
  * keep context objects in our CLPlatform objects
  */
class CLContext (val id: Long, val devices: Array[CLDevice]) {

  def includesDevice (device: CLDevice): Boolean = devices.find(_.id == device.id).isDefined

  def createProgram (src: String): CLProgram = withMemoryStack { stack =>
    val err = stack.allocInt
    val pid = clCreateProgramWithSource(id,src,err)
    checkCLError(err)
    new CLProgram(pid,this)
  }

}
