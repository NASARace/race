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
import org.lwjgl.opencl.CL10.{clEnqueueReadBuffer, clEnqueueWriteBuffer}

/**
  * CLBuffers that are backed by arrays
  * there are two lines of specialization: element type (Short,Int,Float,Double) and buffer access (R,W,RW)
  *
  * Unfortunately we cannot make this generic since the underlying LWJGL clEnqueue..() methods
  * are overloaded and the compiler needs the concrete type (only Short,Int,Float,Double are supported)
  */
abstract class ArrayBuffer[T](val data: Array[T], val tSize: Int) extends CLBuffer {
  val size: Long = data.length * tSize
  def length = data.length
}

abstract class ShortArrayBuffer (data: Array[Short]) extends ArrayBuffer[Short](data,2)
abstract class IntArrayBuffer (data: Array[Int]) extends ArrayBuffer[Int](data,4)
abstract class FloatArrayBuffer (data: Array[Float]) extends ArrayBuffer[Float](data,4)
abstract class DoubleArrayBuffer (data: Array[Double]) extends ArrayBuffer[Double](data,8)


//--- Array buffers - unfortunately we need the concrete array type for the respective clEnqueue.. calls

//--- ShortArray
class ShortArrayRBuffer(val id: Long, data: Array[Short], val context: CLContext) extends ShortArrayBuffer(data) {
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}
class ShortArrayWBuffer (val id: Long, data: Array[Short], val context: CLContext) extends ShortArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
}
class ShortArrayRWBuffer(val id: Long, data: Array[Short], val context: CLContext) extends ShortArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}

//--- IntArray
class IntArrayRBuffer(val id: Long, data: Array[Int], val context: CLContext) extends IntArrayBuffer(data) {
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}
class IntArrayWBuffer (val id: Long, data: Array[Int], val context: CLContext) extends IntArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
}
class IntArrayRWBuffer(val id: Long, data: Array[Int], val context: CLContext) extends IntArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}

//--- FloatArray
class FloatArrayRBuffer(val id: Long, data: Array[Float], val context: CLContext) extends FloatArrayBuffer(data) {
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}
class FloatArrayWBuffer (val id: Long, data: Array[Float], val context: CLContext) extends FloatArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
}
class FloatArrayRWBuffer(val id: Long, data: Array[Float], val context: CLContext) extends FloatArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}

//--- DoubleArray
class DoubleArrayRBuffer(val id: Long, data: Array[Double], val context: CLContext) extends DoubleArrayBuffer(data) {
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}
class DoubleArrayWBuffer (val id: Long, data: Array[Double], val context: CLContext) extends DoubleArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
}
class DoubleArrayRWBuffer(val id: Long, data: Array[Double], val context: CLContext) extends DoubleArrayBuffer(data) {
  def enqueueRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,false,0,data,null,null).?
  def enqueueBlockingRead(queue: CLCommandQueue): Unit = clEnqueueReadBuffer(queue.id,id,true,0,data,null,null).?
  def enqueueWrite(queue: CLCommandQueue): Unit = clEnqueueWriteBuffer(queue.id,id,false,0,data,null,null).?
}