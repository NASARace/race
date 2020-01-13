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
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{RaceActor, RaceContext}
import gov.nasa.race.ifSome

/**
  * root type for socket data acquisition threads
  */
abstract class SocketDataAcquisitionThread(socket: Socket) extends Thread {

  var isDone: AtomicBoolean = new AtomicBoolean(false)

  setDaemon(true) // data acquisition threads are always daemons

  // this can be called from the outside and has to be thread safe
  def terminate: Unit = {
    isDone.set(true)
  }
}

/**
  * actor trait that reads data from a socket
  */
trait SocketImporter extends RaceActor {

  val host = config.getVaultableStringOrElse("host",defaultHost)
  val port = config.getVaultableIntOrElse("port",defaultPort) // no default, needs to be specified
  val initBufferSize = config.getIntOrElse("buffer-size", 4096)

  var socket: Option[Socket] = None
  var thread: Option[SocketDataAcquisitionThread] = None

  //--- override in concrete types
  protected def createDataAcquisitionThread (sock: Socket): Option[SocketDataAcquisitionThread]
  protected def defaultHost: String = "localhost"
  protected def defaultPort: Int

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    try {
      val sock = new Socket(host, port)
      socket = Some(sock)

      thread = createDataAcquisitionThread(sock)
      if (thread.isDefined) super.onInitializeRaceActor(rc, actorConf) else {
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
      t.terminate
      thread = None
    }

    ifSome(socket) { sock =>
      sock.shutdownInput
      sock.close // this should interrupt blocked socket reads in the data acquisition thread
      socket = None
    }

    super.onTerminateRaceActor(originator)
  }
}
