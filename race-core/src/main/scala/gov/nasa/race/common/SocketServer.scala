/*
 * Copyright (c) 2020, United States Government, as represented by the
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

import java.io.{IOException, PrintStream}
import java.net.{ServerSocket, Socket}
import java.util.concurrent.Semaphore

object SocketServer {
  final val noSync = Integer.valueOf(-1)
  final val stdoutSync = Integer.valueOf(1)
  final val stderrSync = Integer.valueOf(2)
}

/**
  * a trait to print to an OutputStream that can write to a server socket
  */
trait SocketServer  {
  import SocketServer._

  def service: String
  def port: Option[Int]
  def fallbackStream: Option[PrintStream]

  def _info(msg: => String): Unit
  def _warning(msg: => String): Unit
  def _error(msg: => String): Unit

  protected var terminateServer = false
  protected val reConnect = new Semaphore(1)  // to signal we should listen again on this port

  protected var sock: Socket = null
  protected var srvThread: Thread = null

  // this is what we export
  protected var out: Option[PrintStream] = None
  private var syncId: Object = noSync

  initializeStream

  protected def initializeStream: Unit = {

    port match {
      case Some(p) =>
        srvThread = new Thread() {
          override def run: Unit = {
            val portSync = Integer.valueOf(p)
            var ssock: ServerSocket = null
            try {
              ssock = new ServerSocket(p)
              do { //--------------------------------------------------------- begin connection loop
                sock = ssock.accept // this blocks until a client connects
                syncId = portSync
                out = Some(new PrintStream(sock.getOutputStream, true))

                _info(s"$service connected")

                reConnect.acquire // wait until we are told to re-connect
              } while (!terminateServer) //------------------------------ end connection loop

            } catch {
              case x:IOException => _error(s"$service failed with $x")

            } finally {
              syncId = noSync
              out = None
              if (sock != null) sock.close
              ssock.close
            }
          }
        }

        srvThread.setDaemon(true)
        srvThread.start

      case None =>
        fallbackStream match {
          case o@Some(ps) =>
            syncId = getSync(ps)
            out = o
          case None =>
            _error(s"no fallback, disable $service")
        }
    }
  }

  protected def getSync (ps: PrintStream): Object = {
    if (ps == System.out) stdoutSync
    else if (ps == System.err) stderrSync
    else Integer.valueOf(ps.hashCode) // TODO - does not syc properly if PrintStreams share the same underlying OutputStream
  }

  def terminate: Unit = {
    if (srvThread != null && srvThread.isAlive) {
      _info(s"$service terminating")
      terminateServer = true
      out = None
      reConnect.release // unblock the server thread
    }
  }

  def ifConnected (f: PrintStream=>Unit): Unit = {
    try {
      val o = out
      if (o != null && o.isDefined){
        syncId.synchronized {
          f(o.get)
        }
      }
    } catch {
      case _: IOException =>
        out = None
        sock.close
        reConnect.release
    }
  }
}
