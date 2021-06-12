/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.common

import akka.stream.BufferOverflowException

import java.io.{IOException, OutputStream}
import java.nio.ByteBuffer

/**
  * an OutputStream that writes to a ByteBuffer
  */
class ByteBufferOutputStream (initialBuf: ByteBuffer, isGrowable: Boolean = false) extends OutputStream {
  protected var buf: ByteBuffer = initialBuf

  def getByteBuffer: ByteBuffer = buf

  // watch out - this replaces the current buffer if isGrowable and otherwise throws an IOException
  protected def grow (minGrowth: Int): Unit = {
    if (isGrowable) {
      val curCapacity = buf.capacity
      val newCapacity = Math.max(curCapacity * 2, curCapacity + minGrowth)
      val newBuf = ByteBuffer.allocate(newCapacity)
      newBuf.put(buf)
      buf = newBuf
    } else throw new IOException("buffer full")
  }

  override def write(bs: Array[Byte]): Unit = write(bs,0,bs.length)

  override def write(bs: Array[Byte], off: Int, len: Int): Unit = {
    try {
      buf.put(bs,off,len)
    } catch {
      case _: BufferOverflowException =>
        grow(len) // this will throw an IOException if not growable
        buf.put(bs,off,len)
    }
  }

  override def write(b: Int): Unit = {
    try {
      buf.put((b & 0xff).toByte)
    } catch {
      case _: BufferOverflowException =>
        grow(1) // this will throw an IOException if not growable
        buf.put((b & 0xff).toByte)
    }
  }
}

/**
  * a ByteBufferOutputStream that can change the underlying ByteBuffer
  */
class SettableByteBufferOutputStream (initialBuf: ByteBuffer, isGrowable: Boolean = false) extends ByteBufferOutputStream(initialBuf,isGrowable) {

  def this () = this(emptyByteBuffer,false)
  def this (isGrowable: Boolean) = this(emptyByteBuffer, isGrowable)

  def setByteBuffer( newBuffer: ByteBuffer): Unit = {
    buf = newBuffer
  }
}