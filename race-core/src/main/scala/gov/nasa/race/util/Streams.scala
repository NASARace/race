/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream, EOFException, IOException, OutputStream}

/**
  * an OutputStream that duplicates to several underlying OutputStreams
  * this is normally used to copy console streams to log files
  */
class TeeOutputStream (val outs: OutputStream*) extends OutputStream {
  override def flush: Unit = outs.foreach(_.flush)
  override def close: Unit = {
    outs.foreach { s =>
      if ((s ne Console.out) && (s ne Console.err)) s.close  // don't close the console output/error streams
    }
  }
  override def write(b: Int): Unit = outs.foreach(_.write(b))
  override def write(bs: Array[Byte]): Unit = outs.foreach(_.write(bs))
  override def write(bs: Array[Byte],off: Int, len: Int): Unit = outs.foreach(_.write(bs,off,len))
}

/**
  * the big output nirvana
  */
class NullOutputStream extends OutputStream {
  override def flush: Unit = {}
  override def close: Unit = {}
  override def write(b: Int): Unit = {}
  override def write(bs: Array[Byte]): Unit = {}
  override def write(bs: Array[Byte],off: Int, len: Int): Unit = {}
}

/**
  * a ByteArrayOutputStream that gives us access to the underlying buffer and control
  * over its position
  */
class SettableBAOStream (size: Int) extends ByteArrayOutputStream(size) {

  def setPosition(newPos: Int): Int = {
    if (newPos >= 0 && newPos < buf.length) {
      count = newPos
      newPos
    } else {
      -1
    }
  }

  def position = count

  def getBuffer = buf
  def getCapacity = buf.length
}

/**
  * a DataOutputStream that uses a SettableBAOStream and provides control over position
  *
  * note this only checks for valid start positions, the caller has to ensure the underlying buffer is
  * large enough to hold the data
  */
class SettableDOStream(baos: SettableBAOStream) extends DataOutputStream(baos) {

  def this (size: Int) = this(new SettableBAOStream(size))

  def setPosition(newPos: Int): Int = baos.setPosition(newPos)
  def position = baos.position

  def getBuffer = baos.getBuffer
  def getCapacity = baos.getCapacity

  def reset = {
    written = 0
    baos.setPosition(0)
  }

  def setByte (pos: Int, v: Byte) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeByte(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  def setChar (pos: Int, v: Char) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeChar(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  def setShort (pos: Int, v: Short) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeShort(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  def setInt (pos: Int, v: Int) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeInt(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  def setLong (pos: Int, v: Long) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeLong(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  def setFloat (pos: Int, v: Float) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeFloat(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  def setDouble (pos: Int, v: Double) = {
    val pos0 = baos.position
    if (baos.setPosition(pos) >= 0) {
      writeDouble(v)
      baos.setPosition(pos0)
    } else throw new IOException("write beyond end of buffer")
  }

  // TODO - not sure we should support setUTF since that is variable length data
}

/**
  * a ByteArrayInputStream that gives us control over buffer position
  */
class SettableBAIStream (buf: Array[Byte]) extends ByteArrayInputStream(buf) {

  def this (size: Int) = this(new Array[Byte](size))

  def position = pos

  def setPosition(newPos: Int) = {
    if (newPos >= 0 && newPos < buf.length) {
      pos = newPos
      newPos
    } else {
      -1
    }
  }

  def getBuffer = buf
  def getCapacity = buf.length
}

class SettableDIStream(bais: SettableBAIStream) extends DataInputStream(bais) {

  def this (size: Int) = this(new SettableBAIStream(size))

  def setPosition(newPos: Int): Int = bais.setPosition(newPos)
  def position = bais.position

  def getBuffer = bais.getBuffer
  def getCapacity = bais.getCapacity

  def peekByte (pos: Int): Byte = {
    val pos0 = bais.position
    if (bais.setPosition(pos) >= 0){
      val v = readByte
      bais.setPosition(pos0)
      v
    } else throw new EOFException
  }

  def peekShort (pos: Int): Short = {
    val pos0 = bais.position
    if (bais.setPosition(pos) >= 0){
      val v = readShort
      bais.setPosition(pos0)
      v
    } else throw new EOFException
  }

  def peekInt (pos: Int): Int = {
    val pos0 = bais.position
    if (bais.setPosition(pos) >= 0){
      val v = readInt
      bais.setPosition(pos0)
      v
    } else throw new EOFException
  }

  def peekLong (pos: Int): Long = {
    val pos0 = bais.position
    if (bais.setPosition(pos) >= 0){
      val v = readLong
      bais.setPosition(pos0)
      v
    } else throw new EOFException
  }

  def peekFloat (pos: Int): Float = {
    val pos0 = bais.position
    if (bais.setPosition(pos) >= 0){
      val v = readFloat
      bais.setPosition(pos0)
      v
    } else throw new EOFException
  }

  def peekDouble (pos: Int): Double = {
    val pos0 = bais.position
    if (bais.setPosition(pos) >= 0){
      val v = readDouble
      bais.setPosition(pos0)
      v
    } else throw new EOFException
  }
}

