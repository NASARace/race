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
import gov.nasa.race.common.BufferRecord
import org.lwjgl.opencl.CL10._

object CLCommandQueue {

  def createCommandQueue (context: CLContext, device: CLDevice, outOfOrder: Boolean = false): CLCommandQueue = {
    withMemoryStack { stack =>
      if (!context.includesDevice(device)) throw new RuntimeException(s"context does not support device ${device.name}")
      val err = stack.allocInt
      val properties = if (outOfOrder) CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE else 0
      val qid = clCreateCommandQueue(context.id, device.id, properties, err)
      checkCLError(err)
      new CLCommandQueue(qid,context,device,outOfOrder)
    }
  }
}

/**
  * wrapper for OpenCL command queue objects
  */
class CLCommandQueue (val id: Long,  val context: CLContext, val device: CLDevice, val outOfOrderExec: Boolean)
                                                                                                 extends CLResource {
  def flush = clFlush(id).?
  def finish = clFinish(id).?
  def enqueueBarrier = clEnqueueBarrier(id).?

  override def release = clReleaseCommandQueue(id).?

  def enqueueTask(kernel: CLKernel) = clEnqueueTask(id,kernel.id,null,null).?

  def enqueue1DRange (kernel: CLKernel, globalWorkSize: Long ) = withMemoryStack { stack =>
    val globWS = stack.allocPointer(globalWorkSize)
    clEnqueueNDRangeKernel(id,kernel.id,1,null,globWS,null,null,null).?
  }

  def enqueueRead[T<:AnyVal] (buffer: AWriteBuf[T],isBlocking: Boolean=false): Unit = buffer.enqueueRead(this,isBlocking)
  def enqueueWrite[T<:AnyVal] (buffer: AReadBuf[T]): Unit = buffer.enqueueWrite(this)

  def enqueueMap (buffer: CLMappedByteBuffer): Unit = buffer.enqueueMap(this) // low level, use executeMapped
  def enqueueUnmap (buffer: CLMappedByteBuffer): Unit = buffer.enqueueUnmap(this) // low level, use executeMapped
  def executeMapped[T](buffer: CLMappedByteBuffer)(f: (ByteBuffer)=>T): T = buffer.executeMapped(this)(f)
  def executeMappedWithRecord[R<:BufferRecord,T](buffer: CLMappedRecordBuffer[R])(f: (R)=>T): T = buffer.executeMappedWithRecord(this)(f)

  def enqueueWaitForEvent (eid: Long): Unit = clEnqueueWaitForEvents(id, eid).? // deprecated as of OpenCL 1.1
}

class CLSyncCommandQueue(id: Long, device: CLDevice, context: CLContext, outOfOrderExec: Boolean)
                                                          extends CLCommandQueue(id,context,device,false) {
  protected val syncBuffer = context.createArrayRBuffer[Int](1)

  override def release = {
    syncBuffer.release
    super.release
  }


}