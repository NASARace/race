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

package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.{Config, ConfigValueFactory}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{ParentRaceActor, RaceContext, SubscribingRaceActor}

/**
  * an actor that does round-robin dispatch to a number of child actors it creates
  * this is a helper actor to avoid bottlenecks (e.g. for message translation)
  *
  * NOTE this doesn't support remote workers, and it is not clear if we really want to
  * since it is a optimization mechanism for which marshalling/un-marshalling would be
  * detrimental
  *
  * TODO - we should add a 'nextAvailable' strategy though, but this would require
  * to wrap the BusEvent so that the feedback could be done outside of the
  * concrete handleEvent
  */
class Dispatcher (val config: Config) extends SubscribingRaceActor with ParentRaceActor {

  val replication = config.getIntOrElse("replication",3)
  val workerConfig = config.getConfig("worker")

  val workers = new Array[ActorRef](replication) // that is redundant with ParentRaceActor but we need speed
  var next = 0

  createWorkers(workerConfig,replication)
  info(s"created $replication workers")

  override def handleMessage = {
    case e: BusEvent =>
      debug(s"dispatching to ${workers(next).path.name}")
      workers(next) ! e
      next = (next + 1) % replication
  }

  def createWorkers (config: Config, replication: Int) = {
    val actorBaseName = config.getString("name")

    for (i <- 1 to replication) yield {
      val actorName = s"$actorBaseName-$i"
      val actorConf = config.withValue("name", ConfigValueFactory.fromAnyRef(actorName))
      info(s"instantiating worker actor $actorName")
      val actorRef = instantiateActor(actorName, actorConf)
      workers(i) = actorRef
    }
  }
}
