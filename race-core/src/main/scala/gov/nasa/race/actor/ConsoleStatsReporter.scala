/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import java.io.PrintWriter

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.ConsoleStats
import gov.nasa.race.core.Messages._
import gov.nasa.race.core.SubscribingRaceActor
import gov.nasa.race.util.ConsoleIO._

import scala.collection.mutable.{SortedMap => MSortedMap}

/**
  * an actor that prints Stats objects on the (ANSI) console
  */
class ConsoleStatsReporter (val config: Config) extends SubscribingRaceActor {

  var opw: Option[PrintWriter] = None
  val topics = MSortedMap.empty[String,ConsoleStats]

  override def onStartRaceActor(originator: ActorRef) = {
    opw = Some(new PrintWriter(System.out))
    super.onStartRaceActor(originator)
  }

  // no need to close if this is the console, but at some point we might want
  // to support configured output streams

  override def handleMessage = {
    case BusEvent(_,stats:ConsoleStats,_) =>
      topics += stats.topic -> stats
      printTopics
  }

  def printTopics = {
    ifSome(opw) { pw =>
      if (topics.nonEmpty) {
        clearScreen
        topics.valuesIterator foreach (_.writeToConsole(pw))
        println
        pw.flush
      }
    }
  }
}
