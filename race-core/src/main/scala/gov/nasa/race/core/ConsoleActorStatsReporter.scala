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
import java.net.Socket

import akka.actor.ActorRef
import gov.nasa.race.common.ActorStats
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
  *
  * can be configured to report remotely by setting system properties `stat.console.host` and
  * `stat.console.port`
  *
  * fixme - this should be a server - use ServerSocket
  */
class ConsoleActorStatsReporter extends ActorStatsReporter {

  implicit val ord: Ordering[ActorStats] = ActorStatsOrdering

  protected[this] var statStream: PrintStream = System.out
  protected[this] var statSocket: Socket = null

  protected[this] val actorStats = mutable.HashMap.empty[ActorRef,ActorStats]
  protected[this] val reportList = mutable.SortedSet.empty[ActorStats]

  initializeStream

  protected def initializeStream: Unit = {
    // we could take this from our config since we are not going to report stats before
    // Akka is initialized but we rather keep it consistent with logging
    var host = System.getProperty("stat.console.host")
    var portSpec = System.getProperty("stat.console.port")

    if (host != null || portSpec != null) {
      if (host == null) host = "127.0.0.1" // default localhost
      if (portSpec == null) portSpec = "50005" // default port

      try {
        val port = Integer.parseInt(portSpec)
        statSocket = new Socket(host, port)
        statStream = new PrintStream(statSocket.getOutputStream, true)

      } catch {
        case t: Throwable =>
          ConsoleIO.printlnErr(s"[ERROR] failed to open stat socket: ${t.getMessage}")
          // restore fallbacks
          if (statSocket != null) {
            statSocket.close
            statSocket = null
          }
          statStream = System.out
      }
    }
  }

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
    val out = statStream

    def indent (lvl: Int): Int = {
      var i=0
      while (i<lvl) { out.print("  "); i+= 1 }
      lvl * 2
    }

    reportList.clear
    actorStats.foreach{ e => reportList.addOne(e._2) }

    out.print(ConsoleIO.ClearScreen)

    out.print(ConsoleIO.infoColor)
    out.println("                                                                             latency [μs]")
    out.println("name                                                     msgs       hb      avg    min    max     sigma")
    out.println("--------------------------------------------------   --------   ------   ------ ------ ------   -------")
    out.print(ConsoleIO.resetColor)

    reportList.foreach { as =>
      if (as.isUnresponsive) out.print(ConsoleIO.errorColor)

      var n = indent(as.level)
      out.print(as.name)
      n += as.name.length
      while (n < 50) { out.print(' '); n += 1}

      val stats = as.latencyStats
      out.print(f"   ${as.msgCount}%8d")
      out.print(f"   ${stats.numberOfSamples}%6d")
      out.print(f"   ${stats.mean / 1000}%6d")
      out.print(f" ${stats.min / 1000}%6d")
      out.print(f" ${stats.max / 1000}%6d")
      out.print(f"   ${stats.sigma / 1000000}%7.2f")
      out.println

      if (as.isUnresponsive) out.print(ConsoleIO.resetColor)
    }
  }

  def terminate: Unit = {
    if (statSocket != null) {
      statSocket.close
      statSocket = null
      statStream = System.out
    }
  }
}
