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

/**
  * tool to list OpenCL platforms
  */
object ListCLPlatforms {

  def main (args: Array[String]): Unit = {
    CLPlatform.platforms.foreach { platform =>

      println(f"\n------ platform ${platform.index}: [0x${platform.id}%X]")
      println(s"  name:                ${platform.name}")
      println(s"  vendor:              ${platform.vendor}")
      println(s"  version:             ${platform.version}")
      println(s"  full profile:        ${platform.isFullProfile}")
      println("  extensions: ")
      platform.extensions.foreach{ e=> println(s"                       $e")}
      println(s"  number of devices:   ${platform.devices.length}")
      println()

      platform.devices.foreach { device =>
        println(f"------ device ${platform.index},${device.index}: [0x${device.id}%X]")
        println(s"  type:              ${device.deviceType}")
        println(s"  name:              ${device.name}")
        println(s"  vendor:            ${device.vendor}")
        println(s"  device version:    ${device.deviceVersion}")
        println(s"  driver version:    ${device.driverVersion}")
        println(s"  C version:         ${device.cVersion}")
        println(s"  full profile:      ${device.isFullProfile}")
        println(s"  available:         ${device.isAvailable}")
        println(s"  compiler:          ${device.isCompilerAvailable}")
        println(s"  max compute units: ${device.maxComputeUnits}")
        println(s"  max workitem dim:  ${device.maxWorkItemDimensions}")
        println(s"  max workgrp size:  ${device.maxWorkGroupSize}")
        println(s"  address bits:      ${device.addressBits}")
        println(s"  max clock freq:    ${device.maxClockFrequency}")
        println(s"  fp64 support:      ${device.isFp64}")
        println(s"  little endian:     ${device.isLittleEndian}")
        println("  extensions: ")
        device.extensions.foreach{ e=> println(s"                     $e")}
        println()
      }
    }
  }
}
