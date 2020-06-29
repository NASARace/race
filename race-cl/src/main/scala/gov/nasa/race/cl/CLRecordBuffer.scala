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
import java.nio.{ByteBuffer, ByteOrder}

import gov.nasa.race.common.BufferRecord
import org.lwjgl.opencl.CL10._

object CLRecordBuffer {
  private def _createBuffer(context: CLContext, buf: ByteBuffer, flags: Int): Long = {
    withMemoryStack { stack =>
      val err = stack.allocInt
      val bid = clCreateBuffer(context.id, flags, buf, err)
      checkCLError(err)
      bid
    }
  }

  def createRecordRBuffer[R<:BufferRecord](context: CLContext, length: Int, createRecord: (ByteBuffer)=>R): CLRecordRBuffer[R] = {
    val size = createRecord(null).size * length
    val buf = ByteBuffer.allocateDirect(size.toInt).order(context.byteOrder)
    val bid = _createBuffer(context, buf, CL_MEM_READ_ONLY | CL_MEM_USE_HOST_PTR)
    new CLRecordRBuffer[R](bid, length, buf, createRecord, context)
  }

  def createRecordWBuffer[R<:BufferRecord](context: CLContext, length: Int, createRecord: (ByteBuffer)=>R): CLRecordWBuffer[R] = {
    val size = createRecord(null).size * length
    val buf = ByteBuffer.allocateDirect(size.toInt).order(context.byteOrder)
    val bid = _createBuffer(context, buf, CL_MEM_WRITE_ONLY | CL_MEM_USE_HOST_PTR)
    new CLRecordWBuffer[R](bid, length, buf, createRecord, context)
  }

  def createRecordRWBuffer[R<:BufferRecord](context: CLContext, length: Int, createRecord: (ByteBuffer)=>R): CLRecordRWBuffer[R] = {
    val size = createRecord(null).size * length
    val buf = ByteBuffer.allocateDirect(size.toInt).order(context.byteOrder)
    val bid = _createBuffer(context, buf, CL_MEM_READ_WRITE | CL_MEM_USE_HOST_PTR)
    new CLRecordRWBuffer[R](bid, length, buf, createRecord, context)
  }
}

/**
  * base type for non-mapped (copied) ByteArray buffers that are accessed through BufferRecords
  * We support these in addition to CLMappedRecordBuffer so that we can pass results around inside the host. It
  * would not make sense to use mapped buffers that have to be copied after each device write
  *
  * note - while we could base this on CLArrayBuffer[Byte] this might cause frequent wrapping since LWJGL does
  * not have APIs that work on Array[Byte] data
  */
trait CLRecordBuffer extends CLBuffer {
  val buf: ByteBuffer
  val size = buf.capacity

  def dump (n: Int=16): Unit = {
    for (i <- 0 until buf.limit) {
      if (i > 0) {
        if (i % n == 0) println() else  print(' ')
      }
      print(f"${buf.get(i)}%02x")
    }
    println()
  }
}

// unfortunately traits cannot have type constraints on type parameters so we need another type
abstract class RecBuf[R<:BufferRecord] extends CLRecordBuffer {
  val createRecord: (ByteBuffer)=>R
  def foreachRecord(f: (R)=>Unit) = createRecord(buf).foreachRecord(f)
}

trait RecWBuf extends CLRecordBuffer {
  def enqueueRead(queue: CLCommandQueue, isBlocking: Boolean=false): Unit = {
    clEnqueueReadBuffer(queue.id,id,isBlocking,0,buf,null,null).?
  }
  def enqueueBlockingRead(queue: CLCommandQueue) = enqueueRead(queue,true)

}

trait RecRBuf extends CLRecordBuffer {
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

// unfortunately we have to do

class CLRecordRBuffer[R<:BufferRecord](val id: Long, val length: Int, val buf: ByteBuffer, val createRecord: (ByteBuffer)=>R,
                                       val context: CLContext) extends RecBuf[R] with RecRBuf

class CLRecordWBuffer[R<:BufferRecord](val id: Long, val length: Int, val buf: ByteBuffer, val createRecord: (ByteBuffer)=>R,
                                       val context: CLContext) extends RecBuf[R] with RecWBuf {

}

class CLRecordRWBuffer[R<:BufferRecord](val id: Long, val length: Int, val buf: ByteBuffer, val createRecord: (ByteBuffer)=>R,
                                        val context: CLContext) extends RecBuf[R] with RecRBuf with RecWBuf