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
package gov.nasa.race.actor

import java.io.{IOException, OutputStream}
import java.net.Socket

import gov.nasa.race.common.ConfigurableStreamCreator.createOutputStream

/**
  * interface to provide filtering capabilities for a SocketArchiver
  */
trait SocketArchiveWriter {
  /**
    * return index+1 of data bytes covered by this write (which does not necessarily correspond to the
    * number of bytes written (e.g. if network messages are \n deliminated lines that could be partially read
    * from the network)
    */
  def write (os: OutputStream, data: Array[Byte], off: Int, len: Int): Int
}


/**
  * SocketArchiveWriter implementation that does not filter anything but indiscriminately writes the
  * data without considering partial network message reads
  */
class DefaultSocketArchiveWriter extends SocketArchiveWriter {
  override def write(os: OutputStream, data: Array[Byte], off: Int, len: Int): Int = {
    os.write(data,off,len)
    off+len
  }
}

class TextLineSocketArchiveWriter extends SocketArchiveWriter {
  /**
    * only write complete \n delimited lines, assuming that 'off' points to a valid beginning of a line
    */
  override def write(os: OutputStream, data: Array[Byte], off: Int, len: Int): Int = {
    var i = off+len-1
    while (i >= 0 && data(i) != '\n') i -= 1
    if (i >= 0) {
      os.write(data,off,i-off+1)
    }
    i+1
  }
}

/**
  * actor that stores (optionally filtered) raw byte data received through a socket into a (optionally compressed) file
  */
trait SocketArchiver extends SocketImporter {

  val writer = getConfigurableOrElse[SocketArchiveWriter]("writer")(createWriter)
  val os = createOutputStream(config)

  // override if there is a subclass specific hardwired writer
  protected def createWriter: SocketArchiveWriter = new DefaultSocketArchiveWriter

  class DataAcquisitionThread (socket: Socket, bufLen: Int) extends SocketDataAcquisitionThread(s"$name-input", socket) {

    override def run: Unit = {
      val buf = new Array[Byte](bufLen)
      val in = socket.getInputStream

      try {
        var limit = in.read(buf,0,buf.length) // blocking point
        var i0 = 0
        var i1 = 0

        while (!isDone.get) {
          if (limit > 0) {
            i1 = writer.write(os, buf, 0, limit)
            if (i1 < limit) { // writer only covered part of the data read
              i0 = limit - i1
              System.arraycopy(buf, i1, buf, 0, i0)
            } else { // writer covered whole data
              i0 = 0
            }
          }
          limit = i0 + in.read(buf,i0,buf.length-i0) // blocking point
        }

      } catch {
        case x:IOException => // ? should we make a reconnection effort here?
      }

      os.flush
      if (os != System.out) os.close
    }
  }

  override protected def createDataAcquisitionThread (sock: Socket): Option[SocketDataAcquisitionThread] = {
    Some(new DataAcquisitionThread(sock,initBufferSize))
  }
}
