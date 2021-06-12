/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.io.InputStream
import java.nio.{ByteBuffer, MappedByteBuffer}
import java.nio.channels.FileChannel
import java.nio.file.{FileSystems, StandardOpenOption}

/**
  * InputStream that wraps generic ByteBuffers
  */
class ByteBufferInputStream (bb: ByteBuffer) extends InputStream {
  protected var buf: ByteBuffer = bb

  override def read: Int = if (!buf.hasRemaining) -1 else buf.get & 0xff

  override def read (bytes: Array[Byte], off: Int, len: Int): Int = {
    val remaining = buf.remaining
    if (remaining >0) {
      val l = Math.min(len, remaining)
      buf.get(bytes,off,l)
      l
    } else -1 // reached end
  }
}

/**
  * a ByteBufferInputStream that can change the underlying ByteBuffer
  */
class SettableByteBufferInputStream (bb: ByteBuffer) extends ByteBufferInputStream(bb) {

  def this () = this(emptyByteBuffer)

  def setByteBuffer (newBuffer: ByteBuffer): Unit = {
    buf = newBuffer
  }

  def getByteBuffer: ByteBuffer = buf
}


/**
  * InputStream that wraps a sliding memory mapped window of a file
  *
  * while this can (depending on storage subsystem) have a dramatic impact on un-compressed data (>50x faster for
  * 40MB file) the effect is ignorable when used as the underlying stream for GZIPInputStream
  */
class MappedByteBufferInputStream (val pathName: String, val mapLen: Long = 1024 * 1024) extends InputStream {

  val path = FileSystems.getDefault.getPath(pathName)
  val fc: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
  val size = fc.size

  protected var mmap: MappedByteBuffer = _
  protected var curBase: Long = 0
  protected var curLimit: Long = 0

  mapNext(0)

  override def close: Unit = {
    mmap = null
  }

  override def available: Int = {
    if (mmap == null) 0 else mmap.remaining
  }

  def mapNext (newBase: Long): Long = {
    val len = Math.min(mapLen, size - newBase)
    mmap = fc.map(FileChannel.MapMode.READ_ONLY, newBase, len) // this should free the previous map
    curBase = newBase
    curLimit = newBase + len
    len
  }

  override def read: Int = {
    if (mmap == null) return -1

    if (mmap.hasRemaining) {
      mmap.get & 0xff

    } else {
      if (curLimit < size) {
        mapNext(curBase + mmap.position())
        mmap.get & 0xff
      } else {
        -1  // reached end
      }
    }
  }

  override def read (bytes: Array[Byte], off: Int, len: Int): Int = {
    if (mmap == null) return -1

    val remaining = mmap.remaining

    if (remaining >= len) {
      mmap.get(bytes,off,len)
      len

    } else {
      if (remaining > 0) {
        mmap.get(bytes, off, remaining) // get what is left in the old mmap
      }

      if (curLimit >= size) {
        if (remaining > 0) remaining else -1

      } else {
        val avail = mapNext(curLimit).toInt
        val l = Math.min(avail, len - remaining)
        mmap.get(bytes, remaining, l)
        l + remaining
      }
    }
  }
}