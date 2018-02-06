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

object CLMappedBuffer {

  def createMappedByteBuffer(context: CLContext,size: Long): CLMappedByteBuffer = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = clCreateBuffer(context.id, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, size, err)
      checkCLError(err(0))
      new CLMappedByteBuffer(bid, size, context)
    }
  }

  def createMappedRecordBuffer[R<:BufferRecord](context: CLContext, length: Int, createRecord: (ByteBuffer)=>R): CLMappedRecordBuffer[R] = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val size = createRecord(null).size*length
      val bid = clCreateBuffer(context.id, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, size, err)
      checkCLError(err(0))
      new CLMappedRecordBuffer[R](bid, length, size, createRecord, context)
    }
  }
}

/**
  * a ByteBuffer based buffer that is mapped into the host
  * NOTE - the host can only access the buffer after it has been mapped, the device only will see the host data
  * after it has been unmapped. Hence there is no point of pre-allocating a ByteBuffer and we should only have one
  * available inside of a executeMapped{} block
  */
class CLMappedByteBuffer(val id: Long, val size: Long, val context: CLContext) extends CLBuffer {
  var data: ByteBuffer = null

  def isMapped = data != null

  def enqueueMap(queue: CLCommandQueue): Unit = synchronized {
    if (data == null) {
      withMemoryStack { stack =>
        val err = stack.allocInt
        data = clEnqueueMapBuffer(queue.id, id, true, CL_MAP_READ | CL_MAP_WRITE, 0, size, null, null, err, null)
        checkCLError(err)
      }
    }
  }

  def enqueueUnmap(queue: CLCommandQueue): Unit = synchronized {
    if (data != null) {
      clEnqueueUnmapMemObject(queue.id, id, data, null, null).?
      data = null
    }
  }

  def executeMapped[T] (queue: CLCommandQueue)(f: (ByteBuffer)=>T): T = synchronized {
    enqueueMap(queue)
    val res = f(data)
    enqueueUnmap(queue)
    res
  }
}

/**
  * a MappedByteBuffer that is only accessed on the host side through a BufferRecord
  */
class CLMappedRecordBuffer[R<:BufferRecord](id: Long, val length: Int, size: Long, createRecord: (ByteBuffer)=>R, context: CLContext)
                                                                         extends CLMappedByteBuffer(id, size, context) {
  def executeMappedWithRecord[T] (queue: CLCommandQueue)(f: (R)=>T): T = synchronized {
    enqueueMap(queue)
    val res = f(createRecord(data))
    enqueueUnmap(queue)
    res
  }
}