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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream, EOFException, IOException, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

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
  * nirvana can be shared
  */
object NullOutputStream extends OutputStream {
  override def flush: Unit = {}
  override def close: Unit = {}
  override def write(b: Int): Unit = {}
  override def write(bs: Array[Byte]): Unit = {}
  override def write(bs: Array[Byte],off: Int, len: Int): Unit = {}
}

/**
  * the ultimate nothing input
  * we definitely need just one of these
  */
object NullInputStream extends InputStream {
  override def read(): Int = -1
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

  def hexDump: Unit = {
    var i = 0;
    while (i < count) {
      var j = 0
      while (i < count && j < 16){
        print(f"${buf(i)}%02x ")
        i += 1
        j += 1
      }
      println()
    }
  }
}

/**
  * a DataOutputStream that uses a SettableBAOStream and provides control over position
  *
  * note this only checks for valid start positions, the caller has to ensure the underlying buffer is
  * large enough to hold the data
  */
class SettableDOStream(baos: SettableBAOStream) extends DataOutputStream(baos) {

  def this (size: Int) = this(new SettableBAOStream(size))

  def setPosition(newPos: Int): Int = {
    written = newPos
    baos.setPosition(newPos)
  }
  def position = baos.position

  def getBuffer = baos.getBuffer
  def getCapacity = baos.getCapacity

  def clear = {
    written = 0
    baos.setPosition(0)
  }

  def hexDump =  baos.hexDump

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
  * a DataOutputStream that can change the underlying OutputStream
  */
class SettableDataOutputStream (os: OutputStream) extends DataOutputStream(os) {
  def setOutputStream (newOut: OutputStream, closePrevious: Boolean = false): Unit = {
    if (closePrevious) out.close()
    out = newOut
  }

  def getOutputStream: OutputStream = out
}

/**
  * a DataInputStream that can change the underlying InputStream
  */
class SettableDataInputStream (is: InputStream) extends DataInputStream(is) {
  def setInputStream (newIn: InputStream): Unit = {
    in = newIn
  }

  def getInputStream: InputStream = in
}

/**
  * a ByteArrayInputStream that gives us control over buffer position
  */
class SettableBAIStream (bs: Array[Byte]) extends ByteArrayInputStream(bs) {

  def this () = this(Array.empty[Byte])
  def this (size: Int) = this(new Array[Byte](size))

  def position = pos

  def setBuffer (newBuf: Array[Byte]): Unit = {
    buf = newBuf
    pos = 0
    count = newBuf.length
  }

  def setPosition(newPos: Int) = {
    if (newPos >= 0 && newPos < buf.length) {
      pos = newPos
      newPos
    } else {
      -1
    }
  }

  def setCount (newCount: Int): Unit = {
    count = if (newCount < buf.length) newCount else buf.length
  }

  def getBuffer = buf
  def getCapacity = buf.length
  def getRemainingByteBuffer(): ByteBuffer = ByteBuffer.wrap(bs,pos,count-pos)
}

class SettableDIStream(bais: SettableBAIStream) extends DataInputStream(bais) {

  def this (size: Int) = this(new SettableBAIStream(size))

  def setPosition(newPos: Int): Int = bais.setPosition(newPos)
  def position = bais.position

  def setCount (newCount: Int): Unit = bais.setCount(newCount)

  def getBuffer = bais.getBuffer
  def getCapacity = bais.getCapacity

  def clear = setPosition(0)

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

/**
  * a GZIPInputStream that does not silently do short reads if the inflater buffer is too small for the request
  *
  * note this still can do short reads if there is not enough data left in the underlying stream or
  * the GzInputStream was created in non-blocking mode.
  */
class GzInputStream (is: InputStream, isBlocking: Boolean = false, bufferSize: Int = 65536) extends GZIPInputStream(is,bufferSize) {

  override def read (buf: Array[Byte], off: Int, len: Int): Int = {
    var remaining = len
    var idx = off
    var nRead = 0
    while (remaining > 0 && (isBlocking || available > 0)) {
      val n = super.read(buf,idx,remaining)
      nRead += n
      if (n < 0) return nRead

      idx += n
      remaining -= n
    }
    nRead
  }
}

