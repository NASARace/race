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

import java.nio.ByteOrder

import gov.nasa.race.cl.CLUtils._
import org.lwjgl.opencl.CL._
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.CL11.CL_DEVICE_OPENCL_C_VERSION
import org.lwjgl.opencl.CL12

import scala.collection.immutable.HashSet

/**
  * device type enumeration
  */
sealed abstract class CLDeviceType (val flags: Long) {
  def isDefault = (flags & CL_DEVICE_TYPE_DEFAULT) != 0
  override def toString: String = {
    var s = getClass.getSimpleName
    if (isDefault) s += " (default)"
    s
  }
}
case class CPU (override val flags: Long) extends CLDeviceType(flags)
case class GPU (override val flags: Long)  extends CLDeviceType(flags)
case class Accelerator (override val flags: Long) extends CLDeviceType(flags)
case class UnknownDeviceType (override val flags: Long) extends CLDeviceType(flags)

object CLDevice {
  val discreteVendorRE = "(?i).*(NVidia|AMD).*".r // luckily they don't build CPUs
  def isDiscreteVendor(vendor: String): Boolean = discreteVendorRE.findFirstIn(vendor).isDefined
}

/**
  * wrapper for OpenCL device object
  *
  * CLDevice in general is the primary user-facing object and hence is used as a facade that encapsulates/hides
  * the context, with the rationale that shared context objects are specialties that come with potential pitfalls
  * like multiple buffer allocations etc. We still provide methods to explicitly specify context objects and/or
  * create queues and programs, but the single device/context/queue is our reference model
  */
class CLDevice (val index: Int, val id: Long, val platform: CLPlatform) extends CLResource {
  import CLDevice._

  val capabilities = createDeviceCapabilities(id, platform.capabilities)

  val deviceType: CLDeviceType = {
    val flags = getDeviceInfoLong(id, CL_DEVICE_TYPE)
    if ((flags & CL_DEVICE_TYPE_CPU) != 0) CPU(flags)
    else if ((flags & CL_DEVICE_TYPE_GPU) != 0) GPU(flags)
    else if ((flags & CL_DEVICE_TYPE_ACCELERATOR) != 0) Accelerator(flags)
    else UnknownDeviceType(flags)
  }
  def isGPU: Boolean = deviceType.isInstanceOf[GPU]
  def isCPU: Boolean = deviceType.isInstanceOf[CPU]
  def isAccelerator: Boolean = deviceType.isInstanceOf[Accelerator]

  def isDiscrete: Boolean = isDiscreteVendor(vendor)
  def isIntegrated: Boolean = !isDiscreteVendor(vendor)
  def isDiscreteGPU: Boolean = isGPU && isDiscrete
  def isIntegratedGPU: Boolean = isGPU && isIntegrated

  val isAvailable: Boolean = getDeviceInfoInt(id, CL_DEVICE_AVAILABLE) == 1
  val isCompilerAvailable: Boolean = getDeviceInfoInt(id, CL_DEVICE_COMPILER_AVAILABLE) == 1

  val name: String = getDeviceInfoStringUTF8(id, CL_DEVICE_NAME)
  val vendor: String = getDeviceInfoStringUTF8(id, CL_DEVICE_VENDOR)
  val vendorId: Int = getDeviceInfoInt(id, CL_DEVICE_VENDOR_ID)

  val isFullProfile: Boolean = getDeviceInfoStringUTF8(id, CL_DEVICE_PROFILE).equalsIgnoreCase("FULL_PROFILE")
  val deviceVersion: String = getDeviceInfoStringUTF8(id, CL_DEVICE_VERSION)
  val driverVersion: String = getDeviceInfoStringUTF8(id, CL_DRIVER_VERSION)
  val cVersion: String = getDeviceInfoStringUTF8(id, CL_DEVICE_OPENCL_C_VERSION) // >= OpenCL 1.1

  val addressBits: Int = getDeviceInfoInt(id, CL_DEVICE_ADDRESS_BITS)
  val maxClockFrequency: Int = getDeviceInfoInt(id, CL_DEVICE_MAX_CLOCK_FREQUENCY)

  val maxComputeUnits: Int = getDeviceInfoInt(id, CL_DEVICE_MAX_COMPUTE_UNITS)
  val maxWorkItemDimensions: Int = getDeviceInfoInt(id, CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS)
  val maxWorkGroupSize: Int = getDeviceInfoLong(id, CL_DEVICE_MAX_WORK_GROUP_SIZE).toInt

  val extensions: HashSet[String] = HashSet.from(getDeviceInfoStringUTF8(id,CL_DEVICE_EXTENSIONS).split(" "))
  val isFp64: Boolean = extensions.contains("cl_khr_fp64")

  val isLittleEndian: Boolean = getDeviceInfoInt(id, CL_DEVICE_ENDIAN_LITTLE) == 1
  def byteOrder = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

  override def release = CL12.clReleaseDevice(id)

  override def toString: String = s"${deviceType}(name=$name)"

  // a single device context
  def createContext: CLSingleDeviceContext = CLContext.createSingleDeviceContext(this)

  def createCommandQueue(context: CLContext, outOfOrder: Boolean=false): CLCommandQueue = {
    CLCommandQueue.createCommandQueue(context,this,outOfOrder)
  }
  def createJobCommandQueue(context: CLContext): CLJobCommandQueue = {
    CLCommandQueue.createJobCommandQueue(context,this)
  }

  def buildProgram (program: CLProgram, options: String = ""): Unit = {
    clBuildProgram(program.id,id,options,null,0).?
  }

}
