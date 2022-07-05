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

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.DataAcquisitionThread
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{RaceActor, RaceContext}
import gov.nasa.race.ifSome

import java.io.IOException

/**
  * root type for socket data acquisition threads
  */
abstract class SocketDataAcquisitionThread(name: String) extends DataAcquisitionThread {
  setName(name)
}

/**
  * actor trait that reads data from a socket
  */
trait SocketImporter extends RaceActor {

  val host = config.getVaultableStringOrElse("host",defaultHost)
  val port = config.getVaultableIntOrElse("port", defaultPort)
  val initBufferSize = config.getIntOrElse("buffer-size", 4096)

  var socket: Option[Socket] = None
  var thread: Option[SocketDataAcquisitionThread] = None


  //--- override in concrete types
  protected def createDataAcquisitionThread (sock: Socket): Option[SocketDataAcquisitionThread]
  protected def defaultHost: String = "localhost"
  protected def defaultPort: Int = 4242

  // override if we need to set socket state (soTimeout etc.)
  protected def createSocket(): Socket = new Socket(host, port)

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    try {
      val sock = createSocket()
      socket = Some(sock)

      thread = createDataAcquisitionThread(sock)
      if (thread.isDefined) super.onInitializeRaceActor(rc, actorConf) else {
        sock.close()
        error("failed to create data acquisition thread")
        false
      }

    } catch {
      case x: Throwable  =>
        ifSome (socket) { sock => sock.close }
        error(s"failed to initialize: $x")
        false
    }
  }

  override def onStartRaceActor(originator: ActorRef) = {
    ifSome(thread) { t => t.start }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    // make sure to let thread know about termination before pulling the rug under its feet by closing the socket
    ifSome(thread) { t =>
      t.terminate()
      thread = None
    }

    ifSome(socket) { sock =>
      sock.close // this should interrupt blocked socket reads in the data acquisition thread
      socket = None
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
      socket = None

      ifSome(socket) { sock =>
        try {
          sock.close()
        } catch {
          case iox: IOException => // ignore, we are going to retry anyway
        }
      }

      try {
        val newSock = new Socket(host, port)
        if (newSock.isConnected) {
          socket = Some(newSock)
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
