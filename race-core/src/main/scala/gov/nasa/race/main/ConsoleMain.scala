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

package gov.nasa.race.main

import java.io.File

import akka.event.Logging
import gov.nasa.race._
import gov.nasa.race.core.RaceActorSystem
import gov.nasa.race.util.ConsoleIO
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.util.FileUtils._

import scala.collection.Seq

/**
  * a RACE driver that runs RACE interactively from the command line,
  * parsing command line options, reading config file arguments, instantiating
  * RaceActorSystems and then giving the user a textual menu for interaction
  *
  * we factor this out into a trait so that client projects can easily create
  * their own Main classes
  */
trait ConsoleMainBase extends MainBase {

  def main(args: Array[String]): Unit = {
    ifSome(getOptions(args)) { opts =>
      setSystemProperties(opts)
      setConsoleUserInfoFactory

      if (initConfigVault(opts)) {
        val universes = instantiateRaceActorSystems(opts.configFiles, opts.logLevel)
        if (universes.nonEmpty) {
          runUniverses(universes, opts.delayStart)
        } else {
          println("no RaceActorSystem to execute, exiting")
        }
      } else {
        println("config vault not initialized, exiting")
      }
    }
  }

  /**
    * get initialized Option[MainOpts], return None if initialization fails
    * override this if you have specialized MainOpts
    */
  def getOptions(args: Array[String]): Option[MainOpts] = CliArgs(args)(new MainOpts("race"))

  //--- main control loop functions

  /**
    *  interactive run (menu) loop that controls the simulation
    */
  def runUniverses(universes: Seq[RaceActorSystem], delayStart: Boolean): Unit = {
    val vmShutdownHook = addShutdownHook(universes.foreach(shutDown)) // ctrl-C (user) termination
    RaceActorSystem.addTerminationListener(() => systemExit())

    if (!delayStart) universes.foreach { ras =>
      if (!ras.delayLaunch) {
        launch(ras)
      }
    }

    menu("enter command [1:show universes, 2:show actors, 3:show channels, 4:send message, 5:set loglevel, 6:app menu, 7: pause/resume, 8:start, 9:exit]\n") {
      case "1" | "universes" => showUniverses(universes)
        repeatMenu

      case "2" | "actors" => runOnSelectedUniverse(universes) { showActors }
        repeatMenu

      case "3" | "channels" => runOnSelectedUniverse(universes) { showChannels }
        repeatMenu

      case "4" | "message" => runOnSelectedUniverse(universes) { sendMessage }
        repeatMenu

      case "5" | "log" => runOnSelectedUniverse(universes) { setLogLevel }
        repeatMenu

      case "6" | "app" => runOnSelectedUniverse(universes) { appMenu }
        repeatMenu

      case "7" | "pause" | "resume" => runOnSelectedUniverse(universes) { pauseResume }
        repeatMenu

      case "8" | "start" => universes.foreach(launch)
        repeatMenu

      case "9" | "exit" =>
        if (RaceActorSystem.hasLiveSystems){     // don't use System.exit here, it would break MultiNodeJVM tests
          if (!RaceActorSystem.isTerminating) {  // don't trip a termination that is already in progress
            println("terminating..")
            //removeShutdownHook(vmShutdownHook)
            RaceActorSystem.removeTerminationListener(() => systemExit())
            universes.foreach(shutDown)
          }
        }
    }
  }

  def showUniverses(universes: Seq[RaceActorSystem]): Unit = {
    for ((u, i) <- universes.zipWithIndex) {
      println(f"${i+1}%3d: ${u.master.path} : ${u.currentStatus}")
    }
  }

  def showActors(ras: RaceActorSystem): Unit = {
    ras.showActors()
  }

  def showChannels(ras: RaceActorSystem): Unit = {
    println(s"channels of universe ${ras.name}:")
    ras.showChannels()
  }

  final val channelPattern = """\| *(\S+)""".r


  def appMenu (ras: RaceActorSystem): Unit = {
    ras.appMenu match {
      case Some(m) => menu(m)
      case None => println(s"universe ${ras.name} has no app menu")
    }
  }

  /**
    * for testing purposes
    * fixme - this should not be in the production system
    */
  def sendMessage(ras: RaceActorSystem): Unit = {
    ConsoleIO.prompt("  enter channel (|<channel-name>) or actor (<actor-name>): \n").foreach { targetSpec =>
      ConsoleIO.prompt("  enter message content or file: \n").foreach { contentSpec =>
        getMessageContent(contentSpec) match {
          case Some(msg) =>
            targetSpec match {
              case channelPattern(channel) => ras.publish(channel, msg)
              case actorSpec: String => ras.send(actorSpec, msg)
            }
          case None =>
        }
      }
    }
  }

  def getMessageContent(contentSpec: String): Option[Any] = {
    if (contentSpec.nonEmpty) {
      if (contentSpec.startsWith("file://")) {
        fileContentsAsUTF8String(new File(contentSpec.substring(7))) match {
          case content @ Some(s) => content
          case None => None
        }
      } else Some(contentSpec)
    } else None
  }

  def setLogLevel(ras: RaceActorSystem): Unit = {
    ConsoleIO.prompt("  enter log level (off,error,warning,info,debug): ").foreach { level =>
      Logging.levelFor(level) match {
        case Some(logLevel) =>
          println(s"changing log level of universe ${ras.name} to $logLevel")
          ras.setLogLevel(logLevel)
        case None => println("invalid log level")
      }
    }
  }

  def pauseResume(ras: RaceActorSystem): Unit = {
    if (ras.isRunning) {
      if (ras.pauseActors) println(s"universe ${ras.name} paused")
      else println(s"universe ${ras.name} pause failed")
    } else if (ras.isPaused) {
      if (ras.resumeActors) println(s"universe ${ras.name} resumed")
      else println(s"universe ${ras.name} resume failed")
    } else println(s"universe ${ras.name} not in state to pause/resume")
  }

  /**
   * override to give user an option in case graceful termination does not work
   */
  override def shutDown(ras: RaceActorSystem): Unit = {
    if (!ras.terminateActors) {
      menu(s"universe termination of ${ras.name} timed out: [1: kill, 2: continue]\n") {
        case "1" | "kill" => ras.kill
        case "2" | "continue" =>
      }
    }
  }


  def runOnSelectedUniverse(universes: Seq[RaceActorSystem])(f: (RaceActorSystem) => Any): Unit = {
    if (universes.length == 1) { // no need to ask
      f(universes.head)
    } else {
      ConsoleIO.promptInt("  enter universe number") match {
        case Some(n) =>
          if (n >= 0 && n < universes.length) f(universes(n))
          else println("invalid universe index")
        case None =>
      }
    }
  }
}

/**
  * the standard RACE driver, which runs RACE interactively from a command line
  */
object ConsoleMain extends ConsoleMainBase