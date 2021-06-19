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
import akka.actor.{Actor, ActorRef, Cancellable}
import akka.event.LoggingAdapter
import com.typesafe.config.Config
import gov.nasa.race.common.{LongStats, ServerSocketPrinter}
import gov.nasa.race.config.ConfigUtils
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.ifSome
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.{ConsoleIO, StringUtils}

import java.lang.management.ManagementFactory
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.ExecutionContext.Implicits.global


/**
  * object to keep track of actor statistics
  */
class ActorStats (val actorRef: ActorRef, val path: String) {
  val level = StringUtils.countOccurrences( path, '/') - 2 // path starts with /<universe>/..
  val name = path.substring(path.lastIndexOf('/')+1)

  var heartBeat: Long = 0
  val latencyStats = new LongStats // in nanos
  var tPing: DateTime = DateTime.Date0  // epoch of last ping

  var msgCount: Long = 0
  var isUnresponsive: Boolean = false
}

object HeartBeat

/**
  * actor to report actor stats in a console
  *
  * note that we don't assume this object is a parent of the monitored actors,
  */
trait MonitorActor  extends Actor with ImplicitActorLogging with ConfigLoggable {

  def config: Config
  def name: String

  val monitorInterval = config.getOverridableDurationOrElse("monitor-interval", Duration.Zero) // default 0 - no monitoring
  val monitorPort = config.getOverridableIntOrElse("monitor-port", 2552)
  val monitorSys = config.getOverridableBooleanOrElse( "monitor-sys", false)
  var monitorSchedule: Option[Cancellable] = None
  var ssp: Option[ServerSocketPrinter] = None

  protected var lastQueryDate: DateTime = DateTime.Date0
  protected var heartBeat: Long = 0
  protected var actorStats = mutable.Map.empty[ActorRef,ActorStats] // can't be a SortedMap since we sort on V, not K
  protected var reportList = ArrayBuffer.empty[ActorStats]

  def initSsp(): Option[ServerSocketPrinter] = {
    if (monitorInterval > Duration.Zero) {
      Some(new ServerSocketPrinter("RaceActorMonitor", monitorPort, 5, this))
    } else {
      None // no point wasting a server thread - not monitoring
    }
  }

  def registerMonitoredActor (queryPath: String, aRef: ActorRef): Unit = {
    aRef ! RegisterRaceActor(self,queryPath)
  }

  def startMonitoring(): Unit = {
    if (monitorInterval > Duration.Zero) {
      monitorSchedule match {
        case Some(sched) => // ignore, already running
        case None =>
          ssp = initSsp() // start listening on monitor port
          info(s"starting actor monitoring on port $monitorPort at $monitorInterval heartBeat interval")
          monitorSchedule = Some(context.system.scheduler.scheduleWithFixedDelay(Duration.Zero, monitorInterval, self, HeartBeat))
      }
    } else {
      info("not monitoring actors, no 'monitor-interval' configured")
    }
  }

  def stopMonitoring(): Unit = {
    monitorSchedule match {
      case Some(sched) =>
        sched.cancel()
        info("stopped actor monitoring")

      case None => // ignore, nothing to stop
    }
  }

  def insertIntoReportList (as: ActorStats): Unit = {
    var i = 0
    while (i < reportList.size) {
      if (reportList(i).path > as.path) {
        reportList.insert(i, as)
        return
      }
      i += 1
    }
    reportList.append(as)
  }

  def handleActorMonitorMessages: Receive = {
    case HeartBeat => processHeartBeat()
    case RaceActorRegistered (queryPath) => registerActor( sender(), queryPath)
    case RaceActorPong (heartBeat, tPing, msgCount) => processPong( sender(), heartBeat, System.nanoTime() - tPing, msgCount)
  }

  def registerActor (aRef: ActorRef, queryPath: String): Unit = {
    info(s"registered actor for monitoring: ${aRef.path} : $queryPath")
    val as = new ActorStats(aRef,queryPath)
    actorStats += aRef -> as
    insertIntoReportList(as)
  }

  def unregisterActor (aRef: ActorRef): Unit = {
    actorStats.get(aRef) match {
      case Some(as) =>
        actorStats -= aRef
        reportList -= as
      case None =>
    }
  }

  def processHeartBeat(): Unit = {
    lastQueryDate = DateTime.now
    checkActorResponses()  // this checks if we got responses for the last heartbeat
    ssp.foreach( _.withPrintStream( report(_,true))) // no periodic reporting if nobody is connected ('show' still works)
    sendPings()  // send the next one
  }

  def sendPings (): Unit = {
    heartBeat += 1

    reportList.foreach { as=>
      if (!as.isUnresponsive) {
        as.heartBeat = heartBeat
        as.tPing = DateTime.now

        as.actorRef ! PingRaceActor(heartBeat, System.nanoTime())
      }
    }
  }

  def processPong (aRef: ActorRef, heartBeat: Long, dt: Long, msgCount: Long): Unit = {
    actorStats.get(aRef) match {
      case Some(as) =>
        if (as.heartBeat == heartBeat && dt >= 0) {
          as.latencyStats += dt
          as.msgCount = msgCount
        }

      case None => // actor not registered, ignore
        warning(s"ignoring Pong from unregistered ${aRef.path}")
    }
  }

  def checkActorResponses (): Unit = {
    reportList.foreach { as=>
      if (as.heartBeat < heartBeat) {
        as.isUnresponsive = true
      }
    }
  }

  def setUnresponsive (aRef: ActorRef): Unit = {
    actorStats.get(aRef) match {
      case Some(as) => as.isUnresponsive = true
      case None => // ignore
    }
  }

  val RemotePathRE = "akka://(.+@.*:\\d+)/.*".r

  def remoteLoc (aRef: ActorRef): Option[String] = {
    aRef.path.toString match {
      case RemotePathRE(loc) => Some(loc)
      case _ => None
    }

  }

  def report (ps: PrintStream, clearScreen: Boolean): Unit = {
    // TODO - doesn't handle long paths (>50 chars) yet

    def indent (ps: PrintStream, lvl: Int): Int = {
      var i=0
      while (i<lvl) { ps.print("  "); i+= 1 }
      lvl * 2
    }

    def spaceNext (n: Int): Int = {
      if (n > 0) {
        var i = 0
        while (i < n) {
          ps.print(' ')
          i += 1
        }
        n
      } else 0
    }

    if (clearScreen) {
      ps.print(ConsoleIO.ClearScreen)
      ps.println(f"monitored actors of RaceActorSystem: $name%-30.30s             at: ${lastQueryDate.formatLocal_yMd_Hms}\n")
    }

    ps.print(ConsoleIO.infoColor)
    ps.println("                                                                      ping-latency [μs]")
    ps.println("name                                           msgs     beat      avg    min    max    sigma   location"  )
    ps.println("----------------------------------------   --------   ------   ------ ------ ------  -------   ---------------------")
    ps.print(ConsoleIO.resetColor)

    reportList.foreach { as =>
      if (as.isUnresponsive) ps.print(ConsoleIO.errorColor)

      var n = indent(ps, as.level)
      ps.print(as.name)
      n += as.name.length

      n += spaceNext(40-n)
      val stats = as.latencyStats
      if (stats.nonEmpty) {
        ps.print(f"   ${as.msgCount}%8d")
        ps.print(f"   ${stats.numberOfSamples}%6d")
        ps.print(f"   ${stats.mean / 1000}%6d")
        ps.print(f" ${stats.min / 1000}%6d")
        ps.print(f" ${stats.max / 1000}%6d")
        ps.print(f"  ${stats.sigma / 1000000}%7.2f")
      } else {
        ps.print("          0        0        -      -      -        -")
      }

      ps.print("   ")
      remoteLoc(as.actorRef) match {
        case Some(loc) => ps.print(loc)
        case None => ps.print("local")
      }

      if (as.isUnresponsive) ps.print(ConsoleIO.resetColor)
      ps.println
    }
    ps.println

    if (monitorSys) reportJVM(ps,false)
  }

  //--- (experimental) JVM monitoring

  val classLoadingMxb = ManagementFactory.getClassLoadingMXBean
  val memoryMxb = ManagementFactory.getMemoryMXBean
  val gcMxb = ManagementFactory.getGarbageCollectorMXBeans
  val threadMxb = ManagementFactory.getThreadMXBean
  val osMxb = ManagementFactory.getOperatingSystemMXBean
  val MB = 1024*1024

  def reportJVM (ps: PrintStream, clearScreen: Boolean): Unit = {
    val loadedClasses = classLoadingMxb.getLoadedClassCount
    val heapMemory = memoryMxb.getHeapMemoryUsage
    val vmMemory = memoryMxb.getNonHeapMemoryUsage
    val threadCount = threadMxb.getThreadCount
    val peakThreadCount = threadMxb.getPeakThreadCount
    val deadlockedThreads = threadMxb.findDeadlockedThreads()
    val deadlockCount = if (deadlockedThreads != null) deadlockedThreads.length else 0
    val sysLoadAvg = osMxb.getSystemLoadAverage

    ps.println(s"heap memory (MB):  used=${heapMemory.getUsed / MB} / committed=${heapMemory.getCommitted / MB} / max=${heapMemory.getMax / MB}")
    ps.println(s"vm memory (MB):    used=${vmMemory.getUsed / MB} / committed=${vmMemory.getCommitted / MB} / max=${vmMemory.getMax / MB}")
    ps.println(s"threads:           live=$threadCount / max=$peakThreadCount / deadlocked=$deadlockCount")
    ps.println(s"classes:           $loadedClasses")
    ps.println(f"system load:       $sysLoadAvg%4.2f%%")
  }
}
