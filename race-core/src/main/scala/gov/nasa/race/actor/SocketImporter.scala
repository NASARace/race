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

import java.net.{ConnectException, Socket}
import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.DataAcquisitionThread
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{RaceActor, RaceContext}
import gov.nasa.race.{core, ifSome}
import gov.nasa.race.util.ThreadUtils

import java.io.IOException
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * message sent to itself by actors using the data acquisition thread
 */
case class ReconnectSocket (dat: SocketDataAcquisitionThread)

/**
  * root type for socket data acquisition threads
  */
abstract class SocketDataAcquisitionThread(name: String, sock: Socket) extends DataAcquisitionThread {
  protected var socket: Socket = sock
  protected var maxErrorCount = 5
  protected var errorCount = 0

  //--- user supplied handler functions (potential blocking points)
  protected var connectionTimeoutHandler: Option[(SocketDataAcquisitionThread)=>Unit] = None
  protected var connectionErrorHandler: Option[(SocketDataAcquisitionThread,Throwable)=>Unit] = None
  protected var connectionLossHandler: Option[(SocketDataAcquisitionThread,Option[Throwable])=>Unit] = None

  def setSocket (newSock: Socket): Unit = socket = newSock // for error recovery (can be called from respective handlers)

  setName(name)

  def setConnectionTimeoutHandler (f: (SocketDataAcquisitionThread)=>Unit): Unit = connectionTimeoutHandler = Some(f)
  def setConnectionErrorHandler (f: (SocketDataAcquisitionThread,Throwable)=>Unit): Unit = connectionErrorHandler = Some(f)
  def setConnectionLossHandler (f: (SocketDataAcquisitionThread,Option[Throwable])=>Unit): Unit = connectionLossHandler = Some(f)
  def setMaxErrorCount(n: Int): Unit =  maxErrorCount = n

  def incErrorCount(): Unit = errorCount += 1
  def resetErrorCount(): Unit = errorCount = 0
  def getErrorCount: Int = errorCount
  def maxErrorCountExceeded: Boolean = errorCount >= maxErrorCount

  // socket is still open
  protected def handleConnectionTimeout(): Unit = {
    connectionTimeoutHandler match {
      case Some(f) =>
        warning(s"connection timeout in data acquisition thread '$name', invoking handler")
        f(this)
      case None =>
        warning(s"connection timeout in data acquisition thread '$name', ignoring")
    }
  }

  // socket is still open - all exceptions other than timeout
  protected def handleConnectionError(x:Throwable): Unit = {
    connectionErrorHandler match {
      case Some(f) =>
        // note that user provided handler is responsible for calling terminate() if connection loss is non-recoverable
        warning(s"connection error in data acquisition thread '$name' ($x), invoking handler")
        f(this, x)
      case None =>
        warning(s"connection error in data acquisition thread '$name' ($x), ignoring")
    }
  }

  // socket is already closed
  protected def handleConnectionLoss(x:Option[Throwable]): Unit = {
    connectionLossHandler match {
      case Some(f) =>
        // note that user provided handler is responsible for calling terminate() if connection loss is non-recoverable
        warning(s"connection loss in data acquisition thread '$name' ($x), invoking handler")
        f(this, x)
      case None =>
        warning(s"connection loss in data acquisition thread '$name' ($x), terminating")
        terminate()
    }
  }
}

/**
  * actor trait that reads data from a socket
  */
trait SocketImporter extends RaceActor {

  val host = config.getVaultableStringOrElse("host",defaultHost)
  val port = config.getVaultableIntOrElse("port", defaultPort)
  val initBufferSize = config.getIntOrElse("buffer-size", 4096)

  var maybeSocket: Option[Socket] = None
  var dataAcquisitionThread: Option[SocketDataAcquisitionThread] = None
  val reconnectInterval: FiniteDuration = config.getFiniteDurationOrElse("reconnect-interval", 30.seconds)

  //--- override in concrete types
  protected def createDataAcquisitionThread (sock: Socket): Option[SocketDataAcquisitionThread]
  protected def defaultHost: String = "localhost"
  protected def defaultPort: Int = 4242

  // those are called during onInitialize

  // NOTE - this is server/application specific, depending on what data is transmitted through the socket
  protected def initializeSocket(sock: Socket): Unit = {}
  protected def initializeDataAcquisitionThread(dat: SocketDataAcquisitionThread): Unit = {}

  // override if we need to set socket state (soTimeout etc.)
  protected def createSocket(): Option[Socket] = Some(new Socket(host, port))

  // watch out - executed in the data acquisition thread
  protected def reconnect (dat: SocketDataAcquisitionThread): Unit = {
    ifSome(maybeSocket) { sock=>
      sock.close() // 'dat' loop needs to see this
      maybeSocket = None
    }

    while (!isTerminating && maybeSocket.isEmpty) {
      ThreadUtils.sleepInterruptible(reconnectInterval) // its fine to sleep - this is not a pooled thread

      try {
        info("trying to reconnect..")
        val maybeSock = createSocket()
        ifSome(maybeSock) { sock =>
          if (sock.isConnected) {
            maybeSocket = maybeSock
            dat.setSocket(sock)
            info("reconnected")
          } else {
            sock.close()
          }
        }
      } catch {
        case x: ConnectException => // ignore, try again
          warning(s"failed to connect: $x")
      }
    }
  }

  def haveOpenSocketInput: Boolean = {
    maybeSocket match {
      case Some(sock) => !(sock.isClosed || sock.isInputShutdown)
      case None => false
    }
  }

  protected def handleSocketTimeout (dat: SocketDataAcquisitionThread): Unit = {
    // nothing - just try again (dat loop should hande maxErrorCount limit)
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    try {
      val maybeSock = createSocket()
      ifSome(maybeSock) { sock =>
        initializeSocket(sock)
        maybeSocket = maybeSock

        val maybeDat = createDataAcquisitionThread(sock)
        ifSome(maybeDat) { t =>
          initializeDataAcquisitionThread(t)
          dataAcquisitionThread = maybeDat
        }
      }
      maybeSocket.isDefined && dataAcquisitionThread.isDefined && super.onInitializeRaceActor(rc,actorConf)

    } catch {
      case x: Throwable  =>
        ifSome (maybeSocket) { sock => sock.close }
        error(s"failed to initialize: $x")
        false
    }
  }

  override def onStartRaceActor(originator: ActorRef) = {
    ifSome(dataAcquisitionThread) { t => t.start }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    // make sure to let thread know about termination before pulling the rug under its feet by closing the socket
    ifSome(dataAcquisitionThread) { t =>
      t.terminate()
      dataAcquisitionThread = None
    }

    ifSome(maybeSocket) { sock =>
      sock.close // this should interrupt blocked socket reads in the data acquisition thread
      maybeSocket = None
    }

    super.onTerminateRaceActor(originator)
  }

  /**
    * this is a last ditch effort to recover from closed Socket streams
    *
    * Note it is the callers responsibility to re-initialize streams and initialize the server (if there is some
    * connection protocol involved)
    */
  def reconnect(): Boolean = {
    if (!isTerminating) {
      maybeSocket = None

      ifSome(maybeSocket) { sock =>
        try {
          sock.close()
        } catch {
          case iox: IOException => // ignore, we are going to retry anyway
        }
      }

      try {
        val newSock = new Socket(host, port)
        if (newSock.isConnected) {
          maybeSocket = Some(newSock)
          true
        } else false // couldn't reconnect
      } catch {
        case iox: IOException =>
          warning(s"reconnecting socket failed: $iox")
          false
      }
    } else false
  }
}
