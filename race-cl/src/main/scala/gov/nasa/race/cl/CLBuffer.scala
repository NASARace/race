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

import java.nio.{ByteBuffer, IntBuffer}

import gov.nasa.race.cl.CLUtils._
import gov.nasa.race.common.BufferRecord
import org.lwjgl.opencl.CL10._
import org.lwjgl.system.MemoryUtil


/**
  * note - while the various buffer creation methods can be used explicitly, they are normally called via
  * respective CLDevice functions since the single device context is the reference model
  *
  * note that read/write are from the device perspective, i.e. the host needs a enqueueWrite for a read buffer
  */
object CLBuffer {
  final val COPY_READ: Long = CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR
  final val COPY_READ_WRITE: Long = CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR


  def createMappedByteBuffer(size: Long, context: CLContext): MappedByteBuffer = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = clCreateBuffer(context.id, CL_MEM_READ_WRITE, size, err)
      checkCLError(err(0))
      new MappedByteBuffer(bid, size, context)
    }
  }
  def createMappedRecordBuffer[R<:BufferRecord](length: Int, createRecord: (ByteBuffer)=>R, context: CLContext): MappedRecordBuffer[R] = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val size = createRecord(null).size*length
      val bid = clCreateBuffer(context.id, CL_MEM_READ_WRITE, size, err)
      checkCLError(err(0))
      new MappedRecordBuffer[R](bid, length, size, createRecord, context)
    }
  }


  //--- ArrayBuffer creation
  private def createArrayBuffer[T<:ArrayBuffer[_]](context: CLContext, f: (IntBuffer)=>Long, g: (Long)=>T): T = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = f(err)
      checkCLError(err(0))
      g(bid)
    }
  }

  //--- IntArrayBuffers
  def createIntArrayW (length: Int, context: CLContext): IntArrayWBuffer = {
    createArrayBuffer(context,
      clCreateBuffer(context.id,CL_MEM_WRITE_ONLY,length*4L,_),
      new IntArrayWBuffer(_,new Array[Int](length),context))
  }
  def createIntArrayR(length: Int, context: CLContext): IntArrayRBuffer = {
    createArrayBuffer(context,
      clCreateBuffer(context.id,CL_MEM_READ_ONLY,length*4L,_),
      new IntArrayRBuffer(_,new Array[Int](length),context))
  }
  def createIntArrayRW(length: Int, context: CLContext): IntArrayRWBuffer = {
    createArrayBuffer(context,
      clCreateBuffer(context.id,CL_MEM_READ_WRITE,length*4L,_),
      new IntArrayRWBuffer(_,new Array[Int](length),context))
  }


  /* TODO get rid of these */
  private def createAndInitArrayBuffer[T <: ArrayBuffer[_]](context: CLContext, f: (Array[Int]) => Long, g: (Long) => T): T = {
    val err = new Array[Int](1)
    val bid = f(err)
    checkCLError(err(0))
    g(bid)
  }

  def createIntArrayW(data: Array[Int], context: CLContext): IntArrayWBuffer = {
    createAndInitArrayBuffer(context,
      clCreateBuffer(context.id, CL_MEM_WRITE_ONLY, data, _),
      new IntArrayWBuffer(_, data, context))
  }

  def createIntArrayR(data: Array[Int], context: CLContext): IntArrayRBuffer = {
    createAndInitArrayBuffer(context,
      clCreateBuffer(context.id, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, _),
      new IntArrayRBuffer(_, data, context))
  }

  def createIntArrayRW(data: Array[Int], context: CLContext): IntArrayRWBuffer = {
    createAndInitArrayBuffer(context,
      clCreateBuffer(context.id, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, data, _),
      new IntArrayRWBuffer(_, data, context))
  }

  def createFloatArrayW(data: Array[Float], context: CLContext): FloatArrayWBuffer = {
    createAndInitArrayBuffer(context,
      clCreateBuffer(context.id, CL_MEM_WRITE_ONLY, data, _),
      new FloatArrayWBuffer(_, data, context))
  }

  def createFloatArrayR(data: Array[Float], context: CLContext): FloatArrayRBuffer = {
    createAndInitArrayBuffer(context,
      clCreateBuffer(context.id, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, _),
      new FloatArrayRBuffer(_, data, context))
  }

  def createFloatArrayRW(data: Array[Float], context: CLContext): FloatArrayRWBuffer = {
    createAndInitArrayBuffer(context,
      clCreateBuffer(context.id, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, data, _),
      new FloatArrayRWBuffer(_, data, context))
  }
   /**/

  //--- float
  def createFloatArrayW (length: Int, context: CLContext): FloatArrayWBuffer = {
    createArrayBuffer(context,
      clCreateBuffer(context.id,CL_MEM_WRITE_ONLY,length*4L,_),
      new FloatArrayWBuffer(_,new Array[Float](length),context))
  }
  def createFloatArrayR(length: Int, context: CLContext): FloatArrayRBuffer = {
    createArrayBuffer(context,
      clCreateBuffer(context.id,CL_MEM_READ_ONLY,length*4L,_),
      new FloatArrayRBuffer(_,new Array[Float](length),context))
  }
  def createFloatArrayRW(length: Int, context: CLContext): FloatArrayRWBuffer = {
    createArrayBuffer(context,
      clCreateBuffer(context.id,CL_MEM_READ_WRITE,length*4L,_),
      new FloatArrayRWBuffer(_,new Array[Float](length),context))
  }

}

/**
  * various wrapper types for OpenCL buffer objects
  *
  * note that we want type safety for:
  *  - content type (array or ByteBuffer)
  *  - buffer access mode (read,write,read-write)
  *  - initialization (copy, map)
  */

trait CLBuffer extends AutoCloseable {
  val id: Long
  val size: Long
  val context: CLContext

  override def close = clReleaseMemObject(id).?
}

/**
  * a ByteBuffer based buffer that is mapped into the host
  * NOTE - the host can only access the buffer after it has been mapped, the device only will see the host data
  * after it has been unmapped. Hence there is no point of pre-allocating a ByteBuffer and we should only have one
  * available inside of a executeMapped{} block
  */
class MappedByteBuffer(val id: Long, val size: Long, val context: CLContext) extends CLBuffer {
  var data: ByteBuffer = null

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

class MappedRecordBuffer[R<:BufferRecord] (id: Long, val length: Int, size: Long, createRecord: (ByteBuffer)=>R, context: CLContext)
                                                     extends MappedByteBuffer(id, size, context) {
  def executeMappedWithRecord[T] (queue: CLCommandQueue)(f: (R)=>T): T = synchronized {
    enqueueMap(queue)
    val res = f(createRecord(data))
    enqueueUnmap(queue)
    res
  }
}


