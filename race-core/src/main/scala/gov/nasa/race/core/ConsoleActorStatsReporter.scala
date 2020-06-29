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
package gov.nasa.race.core

import java.io.PrintStream

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import gov.nasa.race.common.{ActorStats, SocketServer}
import gov.nasa.race.core.Messages.PingRaceActorResponse
import gov.nasa.race.util.ConsoleIO

import scala.collection.mutable

trait ActorStatsReporter {
  def processPingActorResponse (actorRef: ActorRef, msg: PingRaceActorResponse): Unit
  def setUnresponsive (actorRef: ActorRef): Unit
  def report: Unit
  def terminate: Unit
}

object ActorStatsOrdering extends Ordering[ActorStats] {
  def compare (a: ActorStats, b: ActorStats): Int = a.pathString.compareTo(b.pathString)
}

/**
  * object to continuously report actor stats in a console
  */
class ConsoleActorStatsReporter (val port: Option[Int])
                                (implicit val log: LoggingAdapter) extends ActorStatsReporter
                                                                with SocketServer {

  implicit val ord: Ordering[ActorStats] = ActorStatsOrdering

  def service: String = "actor stats reporting"
  def fallbackStream: Option[PrintStream] = Some(System.out)

  def _info(msg: => String): Unit = info(msg)
  def _warning(msg: => String): Unit = warning(msg)
  def _error(msg: => String): Unit = error(msg)

  protected[this] val actorStats = mutable.HashMap.empty[ActorRef,ActorStats]
  protected[this] val reportList = mutable.SortedSet.empty[ActorStats]


  //--- those are the two public methods

  def processPingActorResponse (actorRef: ActorRef, msg: PingRaceActorResponse): Unit = {
    val stats = actorStats.getOrElseUpdate(actorRef, new ActorStats(actorRef))

    stats.msgCount = msg.msgCount
    stats.latencyStats.addSample(msg.latencyNanos)
    stats.isUnresponsive = false
  }

  def setUnresponsive (actorRef: ActorRef): Unit = {
    val stats = actorStats.getOrElseUpdate(actorRef, new ActorStats(actorRef))
    stats.isUnresponsive = true
  }

  def report: Unit = {

    def indent (ps: PrintStream, lvl: Int): Int = {
      var i=0
      while (i<lvl) { ps.print("  "); i+= 1 }
      lvl * 2
    }

    ifConnected { ps =>

      reportList.clear()
      actorStats.foreach { e => reportList.addOne(e._2) }

      ps.print(ConsoleIO.ClearScreen)
      ps.print(ConsoleIO.infoColor)
      ps.println("                                                                             latency [μs]")
      ps.println("name                                                     msgs       hb      avg    min    max     sigma")
      ps.println("--------------------------------------------------   --------   ------   ------ ------ ------   -------")
      ps.print(ConsoleIO.resetColor)

      reportList.foreach { as =>
        if (as.isUnresponsive) ps.print(ConsoleIO.errorColor)

        var n = indent(ps, as.level)
        ps.print(as.name)
        n += as.name.length
        while (n < 50) {
          ps.print(' ');
          n += 1
        }

        val stats = as.latencyStats
        ps.print(f"   ${as.msgCount}%8d")
        ps.print(f"   ${stats.numberOfSamples}%6d")
        ps.print(f"   ${stats.mean / 1000}%6d")
        ps.print(f" ${stats.min / 1000}%6d")
        ps.print(f" ${stats.max / 1000}%6d")
        ps.print(f"   ${stats.sigma / 1000000}%7.2f")
        ps.println

        if (as.isUnresponsive) ps.print(ConsoleIO.resetColor)
      }
    }
  }

}
