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

package gov.nasa.race.actors.imports

import java.io.{IOException, InputStreamReader, BufferedReader}
import java.net.Socket

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.core._
import gov.nasa.race.common._
import gov.nasa.race.common.ConfigUtils._

/**
  * Actor to import ADS-B messages via SBS messages
  *
  * For instance, SBS messages can be generated by dump1090 (https://github.com/MalcolmRobb/dump1090.git),
  * running with
  *   dump1090 --net-sbs-port <port> (default port is 30003)
  *
  */
class SBSImportActor(val config: Config) extends PublishingRaceActor {

  val host = config.getStringOrElse("host", "localhost")
  val port = config.getIntOrElse("port", 30003)

  var sock: Option[Socket] = None // don't connect yet, server might get launched by actor

  val thread = new Thread() {
    override def run: Unit = {
      ifSome(sock) { s =>
        val in = new BufferedReader(new InputStreamReader(s.getInputStream()))
        try {
          while (true) {
            val msg = in.readLine()
            publish(msg)
          }
        } catch {
          case x:IOException =>
        }
      }
    }
  }
  thread.setDaemon(true)

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(rc, actorConf)

    sock = Some(new Socket(host, port))
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    thread.start
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(sock) { _.close }
    super.onTerminateRaceActor(originator)
  }
}
