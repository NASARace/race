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

package gov.nasa.race.actors.process

import java.io.{File, IOException}
import java.lang.ProcessBuilder.Redirect

import akka.actor.{ActorRef, Cancellable, PoisonPill}
import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core.Messages.RaceCheck
import gov.nasa.race.core._

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


/**
  * a RaceActor that is used to launch and monitor external processes
  *
  * Note - we have to use java.lang.Process as the underlying process
  * class since scala.sys.process does not yet support lifetime monitoring
  * (was added by Java 8). Scala 2.12 will add that, so we probably
  * port to scala.sys.process once 2.12 is out
  *
  * TODO - not clear yet to what extend we should support external (dynamic)
  * init, but since this relies on proper local permissions anyways, we
  * do support mixed local/remote argument config, i.e. remote lookup. Remote
  * init is not allowed to change the procName, procEnv or launchDir though
  *
  * TODO - failures to start/terminate process should not un-conditionally throw exceptions
  */
class ProcessLauncher (val config: Config) extends MonitoredRaceActor {

  // those have to come from the ctor args, i.e. from whoever creates this actor
  val procName = config.getString("process-name")
  val procEnv = config.getMapOrElse("process-env", Map.empty)
  val launchDir = config.getExistingDirOrElse("launch-dir", new File("."))
  val logFile = config.getOptionalFile("logfile")
  val appendLog = config.getBooleanOrElse("append-log", false)
  val ensureKill = config.getBooleanOrElse("ensure-kill", false) // do we check & try kill -9

  // those can be amended/overridden by non-ctor config (remote lookup)
  var procArgs = config.getStringListOrElse("process-args", Nil)
  var initLaunch = config.getBooleanOrElse("init-launch", false) // do we launch on init?
  var autoRestart = config.getBooleanOrElse("auto-restart", false)
  val maxRestart = config.getIntOrElse("max-restart", 5)

  var proc: Option[Process] = None
  var restartCount = 0


  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(rc,actorConf)

    def checkIgnored (path: String) = {
      if (actorConf.hasPath(path)){
        warning(s"ignoring remote config $path=${actorConf.getAnyRef(path).toString}")
      }
    }

    if (!isLocalContext(rc)) { // this is a remote config, we only allow some overrides/amendments
      checkIgnored("process-name")
      checkIgnored("process-env")
      checkIgnored("launch-dir")
      if (actorConf.hasPath("process-args")) procArgs = procArgs ++ actorConf.getStringList("process-args")
      if (actorConf.hasPath("init-launch")) initLaunch = actorConf.getBoolean("init-launch")
    }

    if (initLaunch) proc = startProcess
    ifSome(proc){ p=>
      Thread.sleep(1000)
      if (!p.isAlive) throw new IOException(s"process $procName did not start")
      startMonitoring
    }
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)

    if (!proc.isDefined) proc = startProcess
    ifSome(proc){ p=>
      if (!p.isAlive) throw new IOException(s"process $procName did not start")
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)

    ifSome(proc) { p=>
      p.destroy()
      if (ensureKill & p.isAlive){ // try harder
        Thread.sleep(500)
        if (p.isAlive) p.destroyForcibly()
        Thread.sleep(500)
        if (p.isAlive) throw new RaceException(s"ProcessLauncher termintation failed: $procName")
      }
    }
  }

  def procSpec: String = (procName +: procArgs).mkString(" ")

  def startProcess: Option[Process] = {
    val pb = new ProcessBuilder( procName +: procArgs)
    if (procEnv.nonEmpty) {
      val env = pb.environment()
      procEnv.foreach(e => env.put(e._1, e._2))
    }
    pb.directory(launchDir)

    ifSome(logFile) { f=>
      pb.redirectErrorStream(true)
      pb.redirectOutput(if (appendLog) Redirect.appendTo(f) else Redirect.to(f))
    }

    info(s"starting process '$procSpec'")
    Some(pb.start()) // this might throw an IOException or SecurityException, which we pass up
  }

  def checkAlive = {
    ifSome(proc) { p=>
      if (!p.isAlive) {
        if (autoRestart) {
          if (restartCount < maxRestart){
            restartCount += 1
            warning(s"detected death of '$procSpec', restart $restartCount")
            proc = startProcess
          } else commitSuicide(s"detected death of '$procSpec' after $restartCount restarts, terminating")
        } else commitSuicide(s"detected death of '$procSpec', terminating")
      }
    }
  }

  override def handleMessage = {
    case RaceCheck => checkAlive
  }
}
