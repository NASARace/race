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

package gov.nasa.race.launcher

import java.net.Socket
import java.util
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigVault
import gov.nasa.race.core.RaceActorSystem
import gov.nasa.race.main.MainBase
import scala.collection.Seq

/**
 * application object that runs Main without user interaction, to be started via RemoteLauncher.
 *
 * RemoteMain implements the launchee part of RemoteProtocol, i.e. processes commands received
 * through the port the launcher gives us upon startup.
 *
 * Note that MainOpts allow us to specify a host:port for the control socket, but in normal
 * operation RemoteLauncher only give us a port and sets up reverse port forwarding through SSH.
 * Explicit host/port arguments are mostly for testing and local operation.
 * THIS IS WHY WE DON'T ENCRYPT COMMUNICATION
 */
object RemoteMain extends MainBase with RemoteProtocolLaunchee {

  var opts = new RemoteMainOpts()
  protected var universes = Seq.empty[RaceActorSystem]

  def main(args: Array[String]): Unit = {
    if (!opts.parse(args)) return

    val socket = new Socket(opts.host, opts.port) // our control socket, normally tunneled through ssh

    addShutdownHook {
      universes foreach (shutDown)
      socket.close
    }
    RaceActorSystem.addTerminationAction { socket.close() }

    println(s"RemoteMain $pid started")
    processLauncherMessages(socket.getInputStream, socket.getOutputStream, RaceActorSystem.hasLiveSystems)
    println(s"RemoteMain $pid terminated")
  }

  //--- the RemoteProtocolLaunchee implementation

  override def processLaunchCmd(base64Data: String) = {
    val launchData: LaunchConfigSpec = deserializeBase64(base64Data)
    val universeNames = launchData.configs.foldLeft(new StringBuilder) { (sb, conf) =>
      if (sb.nonEmpty) sb.append(',')
      sb.append(conf.getStringOrElse("name", "?"))
    }
    ifSome(launchData.vaultData) { bs =>
      val o: (Config,Array[Byte]) = deserialize(bs)
      ConfigVault.initialize(o)
      util.Arrays.fill(o._2,0.toByte)
    }

    universes = launchData.configs.map { universeConf =>
      println(s"RemoteMain creating universe: ${universeConf.getStringOrElse("name","?")}")
      new RaceActorSystem(universeConf)
    }
    universes.foreach { universe =>
      if (universe.delayLaunch) {
        println(s"RemoteMain delaying launch of universe ${universe.name}")
      } else {
        println(s"RemoteMain launching universe ${universe.name}")
        launch(universe)  // the driver launch() might be overridden
      }
    }
  }

  override def processInspectCmd (topic: String) = {
    println(s"RemoteMain received inspect $topic")
  }

  override def processTerminateCmd = {
    println("RemoteMain terminating")
    universes foreach { _.terminateActors }
  }
}
