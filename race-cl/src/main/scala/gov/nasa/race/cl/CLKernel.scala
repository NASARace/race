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
import org.lwjgl.opencl.CL10._

/**
  * wrapper for OpenCL kernel object
  */
class CLKernel (val id: Long) extends AutoCloseable {
  val name = getKernelInfoStringUTF8(id,CL_KERNEL_FUNCTION_NAME)
  val numArgs = getKernelInfoInt(id,CL_KERNEL_NUM_ARGS)

  override def close = clReleaseKernel(id).?

  def setArgs (args: CLBuffer*): Unit = {
    assert(args.size == numArgs)
    var idx = 0
    args.foreach { a =>
      a match {
        case buf:IntArrayBuffer => clSetKernelArg1p(id,idx,buf.id).?
          // ... and many more
        case _ => throw new RuntimeException(s"unknown argument type for kernel $name: $a")
      }
      idx += 1
    }
  }

}