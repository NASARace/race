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
class SocketLineAcquisitionThread (name: String, _sock: Socket, initSize: Int, maxSize: Int, processLine: ByteSlice=>Boolean)
                                                      extends SocketDataAcquisitionThread(name,_sock) with LogWriter {

  override def run(): Unit = {

    while (!isDone.get()) {
      info(s"(re)setting socket data acquisition loop")
      val sock = socket // might be re-set by connection loss handler
      val is = sock.getInputStream
      val buf = new CanonicalLineBuffer(is, initSize, maxSize)

      while (!isDone.get() && (socket eq sock) && !sock.isClosed ) {
        try {
          if (buf.nextLine()) { // this is the blocking point - note LineBuffer fills automatically from input stream
            resetErrorCount()
            if (!processLine(buf)) isDone.set(true)

          } else { // if we get here the socket input stream returned EOS (but socket might still be open)
            warning(s"socket data acquisition thread received EOS")
            handleConnectionLoss(None)
          }

        } catch {
          case x: InterruptedException => // ignore - the interrupter has to set isDone if we should stop

          //--- note that all handleX() functions are potential blocking points

          case stx: SocketTimeoutException =>
           incErrorCount()
            warning(s"socket data acquisition thread timeout detected ($getErrorCount)")
            if (!isDone.get()) {
              if (maxErrorCountExceeded || sock.isClosed || sock.isInputShutdown) {
                handleConnectionLoss(Some(stx))
              } else {
                handleConnectionTimeout()
              }
            }

          case iox: IOException =>
            if (!isDone.get()) { // this might be caused by termination
              incErrorCount()
              error(s"I/O exception in socket data acquisition thread $iox ($errorCount)")

              if (maxErrorCountExceeded) {
                handleConnectionLoss(Some(iox))
              } else {
                handleConnectionError(iox)
              }
            }
        }
      }
    }
  }
}
