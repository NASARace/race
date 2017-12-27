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

import gov.nasa.race._
import CLUtils._
import org.lwjgl.opencl.CL._
import org.lwjgl.opencl.CL10._
import org.lwjgl.system.MemoryStack

import scala.collection.immutable.HashSet

object CLPlatform {
  val platforms = tryWithResource(MemoryStack.stackPush) { stack =>
    val platformIDs = stack.getCLPointerBuffer((pn, pid) => clGetPlatformIDs( pid, pn))
    platformIDs.mapToArrayWithIndex{ (idx,id) => new CLPlatform(idx,id) }
  }
}

/**
  * OpenCL platform representation
  */
class CLPlatform (val index: Int, val id: Long) {
  val capabilities = createPlatformCapabilities(id)

  val name: String = getPlatformInfoStringUTF8(id, CL_PLATFORM_NAME)
  val vendor: String = getPlatformInfoStringUTF8(id, CL_PLATFORM_VENDOR)

  val version: String = getPlatformInfoStringUTF8(id, CL_PLATFORM_VERSION)
  val isFullProfile: Boolean = getPlatformInfoStringUTF8(id, CL_PLATFORM_PROFILE).equalsIgnoreCase("FULL_PROFILE")

  val extensions: HashSet[String] = HashSet[String](getPlatformInfoStringUTF8(id,CL_PLATFORM_EXTENSIONS).split(" "): _*)

  val devices: Array[CLDevice] = {
    tryWithResource(MemoryStack.stackPush) { stack =>
      val deviceIDs = stack.getCLPointerBuffer((pn, pid) => clGetDeviceIDs(id, CL_DEVICE_TYPE_ALL, pid, pn))
      deviceIDs.mapToArrayWithIndex{ (idx,id) => new CLDevice(idx,id,this) }
    }
  }
}
