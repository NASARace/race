/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import gov.nasa.race.actor.SocketDataAcquisitionThread

import java.io.IOException
import java.net.{Socket, SocketTimeoutException}

/**
  * data acquisition thread that reads text lines from a socket and calls provided processing functions for each
  * complete line that was received. This also supports optional socket timeout processing
  *
  * note the provided callback return values determine if the data acquisition loop is terminated, which does not
  * close the socket (which is the responsibility of the caller)
  *
  * note also that we don't handle line processing exceptions here
  */
class SocketLineAcquisitionThread (name: String, sock: Socket, initSize: Int, maxSize: Int, processLine: ByteSlice=>Boolean)
                                                      extends SocketDataAcquisitionThread(name,sock) with LogWriter {

  override def run(): Unit = {

    while (!isDone.get()) {

      val sock = socket
      val is = sock.getInputStream
      val buf = new CanonicalLineBuffer(is, initSize, maxSize)

      while (!isDone.get() && (sock eq socket) && !sock.isClosed ) {
        try {
          if (buf.nextLine()) { // this is the blocking point - note LineBuffer fills automatically from its InputStream
            if (!processLine(buf)) isDone.set(true)
          }

        } catch {
          case ix: InterruptedException => // ignore - the interrupter has to set isDone if we should stop
            println("@@@ interrupted")

          //--- note that all handleX() functions are potential blocking points

          case x: SocketTimeoutException =>
            println(s"@@@ socket timeout: $x")

            if (sock.isClosed) {
              handleConnectionLoss(x)
            } else {
              handleConnectionTimeout()
            }

          case x: Throwable =>
            println(s"@@@ socket exception: $x")

            if (sock.isClosed) {
              handleConnectionLoss(x)
            } else {
              handleConnectionError(x)
            }
        }
      }
    }

    println("@@@ dat terminated.")
  }
}
