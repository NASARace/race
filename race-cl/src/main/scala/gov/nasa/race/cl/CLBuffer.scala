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

import gov.nasa.race.cl.CLUtils._
import org.lwjgl.opencl.CL10._


/**
  * basis for various wrapper types for OpenCL buffer objects
  *
  * note that we want type safety for:
  *  - content type (array or ByteBuffer)
  *  - buffer access mode (read,write,read-write)
  *  - initialization (copy, map)
  */

trait CLBuffer extends CLResource {
  val id: Long
  val size: Long
  val context: CLContext

  override def release = clReleaseMemObject(id).?
}

//--- generic ByteBuffer support

object CLByteBuffer {
  private def _createBuffer(context: CLContext, size: Long, flags: Int): Long = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = clCreateBuffer(context.id, flags, size, err)
      checkCLError(err)
      bid
    }
  }

  def createByteRBuffer (context: CLContext, buf: ByteBuffer): CLByteRBuffer = {
    buf.order(context.byteOrder)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_ONLY)
    new CLByteRBuffer(id, buf, context)
  }

  def createByteWBuffer (context: CLContext, buf: ByteBuffer): CLByteWBuffer = {
    buf.order(context.byteOrder)
    val id = _createBuffer(context, buf.capacity, CL_MEM_WRITE_ONLY)
    new CLByteWBuffer(id, buf, context)
  }

  def createByteRWBuffer (context: CLContext, buf: ByteBuffer): CLByteRWBuffer = {
    buf.order(context.byteOrder)
    val id = _createBuffer(context, buf.capacity, CL_MEM_READ_WRITE)
    new CLByteRWBuffer(id, buf, context)
  }
}


trait CLByteBuffer extends CLBuffer {
  val buf: ByteBuffer
  val size = buf.capacity
}

trait ByteWBuf extends CLByteBuffer {
  def enqueueRead(queue: CLCommandQueue, isBlocking: Boolean=false): Unit = {
    clEnqueueReadBuffer(queue.id,id,isBlocking,0,buf,null,null).?
  }
  def enqueueBlockingRead(queue: CLCommandQueue) = enqueueRead(queue,true)

}

trait ByteRBuf extends CLByteBuffer {
  def enqueueWrite(queue: CLCommandQueue, isBlocking: Boolean=false): Unit = {
    clEnqueueWriteBuffer(queue.id,id,isBlocking,0,buf,null,null).?
  }
  def enqueueBlockingWrite(queue: CLCommandQueue) = enqueueWrite(queue,true)

  def enqueueWriteEv (queue: CLCommandQueue): Long = withMemoryStack { stack =>
    val pev = stack.allocPointer
    clEnqueueWriteBuffer(queue.id,id,false,0,buf,null,pev).?
    pev(0)
  }

  def enqueueWriteEvent (queue: CLCommandQueue): CLEvent = new CLEvent(enqueueWriteEv(queue),context)
}

class CLByteRBuffer (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf
class CLByteWBuffer (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteWBuf
class CLByteRWBuffer (val id: Long, val buf: ByteBuffer, val context: CLContext) extends ByteRBuf with ByteWBuf

