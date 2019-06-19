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
import gov.nasa.race.cl.CLUtils._
import gov.nasa.race.util.StringUtils
import org.lwjgl.opencl.CL._
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.CLContextCallbackI
import org.lwjgl.system.MemoryUtil

import scala.collection.immutable.HashSet
import scala.util.matching.Regex

object CLPlatform {
  val platforms = withMemoryStack { stack =>
    val platformIDs = stack.getCLPointerBuffer((pn, pid) => clGetPlatformIDs( pid, pn))
    platformIDs.mapToArrayWithIndex{ (idx,id) => new CLPlatform(idx,id) }
  }

  def firstPlatform: CLPlatform = if (platforms.length > 0) platforms(0) else throw new RuntimeException("no OpenCL platform")
  def preferredDevice: CLDevice = firstPlatform.preferredDevice
}

/**
  * wrapper for OpenCL platform object
  */
class CLPlatform (val index: Int, val id: Long) {
  val capabilities = createPlatformCapabilities(id)

  val name: String = getPlatformInfoStringUTF8(id, CL_PLATFORM_NAME)
  val vendor: String = getPlatformInfoStringUTF8(id, CL_PLATFORM_VENDOR)

  val version: String = getPlatformInfoStringUTF8(id, CL_PLATFORM_VERSION)
  val isFullProfile: Boolean = getPlatformInfoStringUTF8(id, CL_PLATFORM_PROFILE).equalsIgnoreCase("FULL_PROFILE")

  val extensions: HashSet[String] = HashSet.from(getPlatformInfoStringUTF8(id,CL_PLATFORM_EXTENSIONS).split(" "))

  val devices: Array[CLDevice] = withMemoryStack { stack =>
    val deviceIDs = stack.getCLPointerBuffer((pn, pid) => clGetDeviceIDs(id, CL_DEVICE_TYPE_ALL, pid, pn))
    deviceIDs.mapToArrayWithIndex{ (idx,id) => new CLDevice(idx,id,this) }
  }


  def firstCPUDevice: Option[CLDevice] = devices.find(_.isCPU) // shouldn't need to be an option
  def firstGPUDevice: Option[CLDevice] = devices.find(_.isGPU)
  def lastGPUDevice: Option[CLDevice] = findLastIn(devices)(_.isGPU)
  def lastDiscreteGPUDevice: Option[CLDevice] = findLastIn(devices)(_.isDiscreteGPU)
  def firstIntegratedGPUDevice: Option[CLDevice] = findFirstIn(devices)(_.isIntegratedGPU)

  def lastFp64Device: Option[CLDevice] = findLastIn(devices)(_.isFp64)
  def lastFp64GPUDevice: Option[CLDevice] = findLastIn(devices)((d) => d.isGPU && d.isFp64)

  def matchingNameGPUDevices (pattern: Regex): Array[CLDevice] = devices.filter(d=>StringUtils.matches(d.name,pattern))
  def matchingVendorGPUDevices (pattern: Regex): Array[CLDevice] = devices.filter(d=>StringUtils.matches(d.vendor,pattern))


  /**
    * prefer GPUs over CPUs, prefer discrete GPUs over internal ones
    * throw exception if there is no device
    */
  def preferredDevice: CLDevice = {
    lastDiscreteGPUDevice.orElse(firstIntegratedGPUDevice).orElse(firstCPUDevice).getOrElse {
      throw new RuntimeException(s"no device found in platform $name")
    }
  }

  def preferredF64Device: CLDevice = lastFp64Device.getOrElse {
    throw new RuntimeException(s"no fp64 device found in platform $name")
  }
}
