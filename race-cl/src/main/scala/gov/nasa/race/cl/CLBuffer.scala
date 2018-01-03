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

import java.nio.IntBuffer

import gov.nasa.race.cl.CLUtils._
import org.lwjgl.opencl.CL10._

/**
  * note - while the various buffer creation methods can be used explicitly, they are normally called via
  * respective CLDevice functions since the single device context is the reference model
  *
  * note that read/write are from the device perspective, i.e. the host needs a enqueueWrite for a read buffer
  */
object CLBuffer {
  final val COPY_WRITE: Long = CL_MEM_WRITE_ONLY | CL_MEM_COPY_HOST_PTR
  final val COPY_READ: Long = CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR
  final val COPY_READ_WRITE: Long = CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR


  //--- ArrayBuffer creation
  private def createArrayBuffer[T<:CLArrayBuffer[_]] (context: CLContext, flags: Long,
                                                      f: (Long,IntBuffer)=>Long, g: (Long)=>T): T = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = f(flags, err)
      checkCLError(err(0))
      g(bid)
    }
  }

  private def createAndInitArrayBuffer[T<:CLArrayBuffer[_]] (context: CLContext, flags: Long,
                                                     f: (Long,Array[Int])=>Long, g: (Long)=>T): T = {
    val err = new Array[Int](1)
    val bid = f(flags,err)
    checkCLError(err(0))
    g(bid)
  }



  //--- IntArrayBuffers
  def createIntArrayCW (length: Int, context: CLContext): IntArrayCWBuffer = withMemoryStack { stack =>
    createArrayBuffer(context,CL_MEM_WRITE_ONLY, clCreateBuffer(context.id,_,length*4L,_), new IntArrayCWBuffer(_,new Array[Int](length),context))
  }
  def createIntArrayCW (data: Array[Int],context: CLContext): IntArrayCWBuffer = {
    createAndInitArrayBuffer(context,COPY_WRITE, clCreateBuffer(context.id,_,data,_), new IntArrayCWBuffer(_,data,context))
  }

  def createIntArrayCR (length: Int,context: CLContext): IntArrayCRBuffer = {
    createArrayBuffer(context,COPY_READ, clCreateBuffer(context.id,_,length*4L,_), new IntArrayCRBuffer(_,new Array[Int](length),context))
  }
  def createIntArrayCR (data: Array[Int],context: CLContext): IntArrayCRBuffer = {
    createAndInitArrayBuffer(context,COPY_READ, clCreateBuffer(context.id,_,data,_), new IntArrayCRBuffer(_,data,context))
  }

  def createIntArrayCRW (length: Int,context: CLContext): IntArrayCRWBuffer = {
    createArrayBuffer(context,COPY_READ_WRITE, clCreateBuffer(context.id,_,length*4L,_), new IntArrayCRWBuffer(_,new Array[Int](length),context))
  }
  def createIntArrayCRW (data: Array[Int],context: CLContext): IntArrayCRWBuffer = {
    createAndInitArrayBuffer(context,COPY_READ_WRITE, clCreateBuffer(context.id,_,data,_), new IntArrayCRWBuffer(_,data,context))
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

abstract class CLArrayBuffer[T](val data: Array[T], val tSize: Int) extends CLBuffer {
  val size: Long = data.length * tSize
  def length = data.length
}
abstract class IntArrayBuffer (data: Array[Int]) extends CLArrayBuffer[Int](data,4)

//--- the concrete buffer classes

//--- IntArray copy buffers
class IntArrayCRBuffer (val id: Long, data: Array[Int], val context: CLContext) extends IntArrayBuffer(data) {
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,true,0,data,null,null).?
}
class IntArrayCWBuffer (val id: Long, data: Array[Int], val context: CLContext) extends IntArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
}
class IntArrayCRWBuffer (val id: Long, data: Array[Int], val context: CLContext) extends IntArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,true,0,data,null,null).?
}