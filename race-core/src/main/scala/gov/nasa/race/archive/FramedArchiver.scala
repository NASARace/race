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

package gov.nasa.race.archive

import java.io.{IOException, InputStream, OutputStream}

/**
  * an ArchiveWriter that stores entries as big endian 32 bit frame size followed by the
  * respective number of payload bytes
  */
trait FramedArchiveWriter extends ArchiveWriter {
  val oStream: OutputStream

  def writeFrameSize (nBytes: Int) = {
    oStream.write((nBytes >> 24) & 0xff)
    oStream.write((nBytes >> 16) & 0xff)
    oStream.write((nBytes >> 8) & 0xff)
    oStream.write(nBytes & 0xff)
  }

  def writeFrame (bytes: Array[Byte]) = oStream.write(bytes)
}

/**
  * an ArchiveReader that reads entries as big endian 32 bit frame size followed by
  * the respective number of payload bytes
  * To safeguard against corrupted archives we limit the frame size to 1MB
  */
trait FramedArchiveReader extends ArchiveReader {
  final val MAX_FRAMESIZE = 1024*1024 // just as a safeguard

  val iStream: InputStream
  private var buf: Array[Byte] = new Array[Byte](1024)

  def readFrameSize: Int = {
    ((iStream.read & 0xff) << 24) +
    ((iStream.read & 0xff) << 16) +
    ((iStream.read & 0xff) << 8) +
    ((iStream.read & 0xff))
  }

  def readFrame (nBytes: Int): Array[Byte] = {
    if (nBytes > buf.length) {
      if (nBytes > MAX_FRAMESIZE) {
        throw new IOException(s"frame exceeding max length: $nBytes")
      }
      buf = new Array[Byte](nBytes)
    }

    if (iStream.read(buf, 0, nBytes) == nBytes) {
      buf
    } else {
      throw new IOException(s"error reading $nBytes from frame archive stream")
    }
  }
}