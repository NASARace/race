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
import gov.nasa.race.tryWithResource
import org.lwjgl.opencl.CL._
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.CL11.CL_DEVICE_OPENCL_C_VERSION
import org.lwjgl.opencl.CLContextCallbackI
import org.lwjgl.system.{MemoryStack, MemoryUtil}

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

/**
  * wrapper for OpenCL device object
  */
class CLDevice (val index: Int, val id: Long, val platform: CLPlatform) {
  val capabilities = createDeviceCapabilities(id, platform.capabilities)

  val deviceType: CLDeviceType = {
    val flags = getDeviceInfoLong(id, CL_DEVICE_TYPE)
    if ((flags & CL_DEVICE_TYPE_CPU) != 0) CPU(flags)
    else if ((flags & CL_DEVICE_TYPE_GPU) != 0) GPU(flags)
    else if ((flags & CL_DEVICE_TYPE_ACCELERATOR) != 0) Accelerator(flags)
    else UnknownDeviceType(flags)
  }

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

  val extensions: HashSet[String] = HashSet[String](getDeviceInfoStringUTF8(id,CL_DEVICE_EXTENSIONS).split(" "): _*)
  val supportsDouble: Boolean = extensions.contains("cl_khr_fp64")

  /** on demand created device context */
  lazy val context: CLContext = withMemoryStack { stack =>
    val err = stack.allocInt
    val ctxProps = stack.allocPointerBuffer(0)
    val errCb = new CLContextCallbackI {
      override def invoke(err_info: Long, private_info: Long, cb: Long, user_data: Long): Unit = {
        System.err.println(s"device context error in $name: ${MemoryUtil.memUTF8(err_info)}")
      }
    }
    val ctxId = clCreateContext(ctxProps,id,errCb,0,err)
    checkCLError(err)
    new CLContext(ctxId)
  }

  /** the on-demand default queue, which uses the device context */
  lazy val queue: CLCommandQueue = createCommandQueue(context)

  def createCommandQueue (context: CLContext, outOfOrder: Boolean=false): CLCommandQueue = withMemoryStack { stack =>
    val err = stack.allocInt
    val properties = if (outOfOrder) CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE else 0
    val qid = clCreateCommandQueue(context.id, id, properties, err)
    checkCLError(err)
    new CLCommandQueue(qid,this,context,outOfOrder)
  }
}
