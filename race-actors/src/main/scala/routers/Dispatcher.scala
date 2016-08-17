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

package gov.nasa.race.actors.routers

import akka.actor.{Props, ActorRef}
import com.github.nscala_time.time.Imports._
import com.typesafe.config.{ConfigValueFactory, Config}
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core._
import gov.nasa.race.core.Messages.{TerminateRaceActor, StartRaceActor, InitializeRaceActor}
import gov.nasa.race.core.{RaceContext, RaceActor, BusEvent, SubscribingRaceActor}

/**
  * an actor that does round-robin dispatch to a number of child actors it creates
  * this is a helper actor to avoid bottlenecks (e.g. for message translation)
  *
  * TODO this doesn't support remote workers or timeouts yet
  */
class Dispatcher (val config: Config) extends SubscribingRaceActor {

  val replication = config.getIntOrElse("replication",3)
  val workerConfig = config.getConfig("worker")
  val workers: Array[ActorRef] = createWorkers(workerConfig,replication)
  var next = 0

  info(s"created $replication workers")

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Unit = {
    super.onInitializeRaceActor(rc, actorConf)
    initDependentRaceActors(workers, rc, workerConfig)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    startDependentRaceActors(workers)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    terminateDependentRaceActors(workers)
  }

  override def handleMessage = {
    case e: BusEvent =>
      info(s"dispatching to ${workers(next).path.name}")
      workers(next) ! e
      next = (next + 1) % replication
  }

  def createWorkers (config: Config, replication: Int): Array[ActorRef] = {
    val actorBaseName = config.getString("name")

    (for (i <- 1 to replication) yield {
      val actorName = s"$actorBaseName-$i"
      val actorConf = config.withValue("name", ConfigValueFactory.fromAnyRef(actorName))
      info(s"instantiating worker actor $actorName")
      instantiateActor(actorName, actorConf)
    }).toArray
  }

}
