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
  * wrapper for OpenCL command queue objects
  */
class CLCommandQueue (val id: Long, val device: CLDevice, val context: CLContext, val outOfOrderExec: Boolean) extends AutoCloseable {

  def flush = clFlush(id).?
  def finish = clFinish(id).?

  override def close = clReleaseCommandQueue(id).?

  def enqueueTask(kernel: CLKernel) = clEnqueueTask(id,kernel.id,null,null).?

  def enqueue1DRange (kernel: CLKernel, globalWorkSize: Long ) = withMemoryStack { stack =>
    val globWS = stack.allocPointer(globalWorkSize)
    clEnqueueNDRangeKernel(id,kernel.id,1,null,globWS,null,null,null).?
  }

  def enqueueRead (buffer: IntArrayCWBuffer): Unit = buffer.enqueueRead(this)
  def enqueueRead (buffer: IntArrayCRWBuffer): Unit = buffer.enqueueRead(this)
  def enqueueWrite (buffer: IntArrayCRBuffer): Unit = buffer.enqueueWrite(this)
  def enqueueWrite (buffer: IntArrayCRWBuffer): Unit = buffer.enqueueWrite(this)
}
