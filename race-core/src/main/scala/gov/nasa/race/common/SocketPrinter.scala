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

import gov.nasa.race.core.Loggable

import java.io.{IOException, PrintStream}
import java.net.{BindException, ConnectException, InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.Semaphore

abstract class SocketPrinter (val service: String, val log: Loggable) {
  protected val lock = new Object() // to synchronize block output to this printstream

  @volatile protected var terminateThread = false
  protected val reConnect = new Semaphore(1)  // to signal we should listen again on this port
  reConnect.acquire()

  protected var out: Option[PrintStream] = None

  // need to be provided by concrete type
  protected var sock: Socket
  protected var sockThread: Thread

  def isConnected: Boolean = out.isDefined

  /**
    * local side termination
    */
  def terminate: Unit = {
    if (sockThread != null && sockThread.isAlive) {
      log.info(s"$service terminating")
      terminateThread = true
      out = None
      reConnect.release // unblock the server thread so that clients can reconnect
    }
  }

  def getPrintStream: Option[PrintStream] = out

  def getPrintStreamOrElse (fallback: PrintStream): PrintStream = out.getOrElse(fallback)

  def withPrintStream (f: PrintStream=>Unit): Unit = {
    out match {
      case Some(ps) =>
        try {
          lock.synchronized(f(ps))
        } catch {
          case _: IOException =>
            out = None
            sock.close
            reConnect.release
        }
      case None => // don't print anything
    }
  }

  def withPrintStreamOrElse (fallback: PrintStream) (f: PrintStream=>Unit): Unit = {
    out match {
      case Some(ps) =>
        try {
          lock.synchronized(f(ps))
        } catch {
          case _: IOException =>
            out = None
            sock.close
            reConnect.release
        }
      case None =>
        lock.synchronized( f(fallback))
    }
  }
}

/**
  * an object that provides a PrintStream writing to a ServerSocket
  * this is one-way - we only print to this port
  * we only support one connection at a time but allow re-connects
  */
class ServerSocketPrinter (service: String, firstPort: Int, nPorts: Int, log: Loggable) extends SocketPrinter(service,log) {

  override var sock: Socket = null

  protected var sockThread: Thread = new Thread() {
    setDaemon(true)
    start()

    override def run: Unit = {
      var ssock: ServerSocket = null
      var port = firstPort
      var n = nPorts

      try {
        while (ssock == null && n > 0) {
          try {
            ssock = new ServerSocket(port)
          } catch {
            case x: BindException =>
              log.warning(s"$service port $port already in use, trying next..")
              port += 1
          }
          n -= 1
        }
        if (ssock != null) {
          log.info(s"$service listening on port $port")

          while (!terminateThread) {
            sock = ssock.accept // this blocks until a client connects
            out = Some(new PrintStream(sock.getOutputStream, true))
            log.info(s"$service connected")

            reConnect.acquire // wait until we are told to listen again
          }
        } else {
          log.warning(s"$service did not find free port in range $firstPort+$nPorts")
        }

      } catch {
        case x: IOException => log.error(s"$service failed with $x")

      } finally {
        out = None
        if (sock != null) sock.close
        if (ssock != null) ssock.close
      }
    }
  }
}

/**
  * an object that provides a PrintStream writing to a client socket, i.e. there needs to be a server listening
  * on the configured port
  *
  * this is one-way - we only print to this port
  * we only support one connection at a time but allow re-connects
  */
class ClientSocketPrinter (service: String, val host: String, port: Int, log: Loggable)  extends SocketPrinter(service,log) {
  private val pass = new Semaphore(1)

  protected val addr = new InetSocketAddress(host,port)
  override protected var sock = new Socket()

  protected var sockThread = new Thread() {
    setDaemon(true)
    start()

    override def run(): Unit = {
      try {
        while (!terminateThread) {
          log.info(s"$service trying to connect to $addr")
          sock.connect(addr)
          pass.release()

          out = Some(new PrintStream(sock.getOutputStream, true))
          log.info(s"$service connected")

          reConnect.acquire // this blocks until we are told to re-connect
        }
      } catch {
        case cx: ConnectException => log.warning(s"$service not connected ($host:$port not available)")
        case x: IOException => log.error(s"$service failed with $x")

      } finally {
        pass.release()
        out = None
        if (sock != null) sock.close
      }
    }
  }

  pass.acquire() // wait until we either have a connection or found out the socket is not open
}
