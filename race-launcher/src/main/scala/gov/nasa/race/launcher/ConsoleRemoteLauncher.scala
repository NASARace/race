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

import java.io.File
import gov.nasa.race._
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.util.{ClassLoaderUtils, CryptUtils}
import scala.collection.Seq

/**
 * an interactive RemoteLauncher that lets the
 * user select the user@host + config + config vault for the respective RemoteMain,
 * and start/terminate respective ssh sessions
 */
object ConsoleRemoteLauncher extends RemoteLauncher with ConsoleLaunchPrompter {

  def main(args: Array[String]): Unit = {
    if (opts.parse(args)) {
      ClassLoaderUtils.initializeCodeSourceMap(RemoteLauncher.codeSourceEntries)
      initializeWithOpts
      initLogging
      installShutdownHook
      setConsoleUserInfoFactory
      startMonitoringSessions
      launcherReady

      menu("enter command [1:add session, 2:show sessions, 3:inspect session, 4:remove session, 5:remove all sessions, 9:exit]:\n") {
        case "1" | "add" => addSession; repeatMenu
        case "2" | "show sessions" => showSessions; repeatMenu
        case "3" | "inspect session" => inspectSession; repeatMenu
        case "4" | "remove session" => removeSession; repeatMenu
        case "5" | "removeall" => removeAllSessions; repeatMenu
        case "9" | "exit" => println("terminating remote launcher.")
      }

      stopMonitoringSessions
    }
  }

  def getVaultData(vaultSpec: Option[(File, Array[Char])]): Option[Array[Byte]] = {
    try {
      vaultSpec match {
        case Some((file,pw)) =>
          val key = CryptUtils.destructiveGetKey(pw)
          for (
            cipher <- CryptUtils.getDecryptionCipher(key);
            config <- CryptUtils.processFile2Config(file,cipher)
          ) yield serialize((config,key.getEncoded))
        case None => None
      }
    } catch {
      case t: Throwable =>
        error(s"config vault processing failed with: $t")
        None
    }
  }

  //--- menu commands

  def addSession = {
    for (
      usr <- promptRemoteUser(opts.user);
      host <- promptRemoteHost(opts.host);
      optIdFile <- promptOptionalIdentity(opts.identityDir.getPath);
      configFile <- promptConfigFile(opts.configDir.getPath);
      optVaultSpec <- promptOptionalVaultSpec;
      label <- promptSessionLabel(s"${filename(opts.remoteCmd)} ${relPath(opts.configDir, configFile)}")
    ) {
      val configs = getUniverseConfigs(Seq(configFile), opts.logLevel)
      val sid = incSessionCount.toString // NOTE this has to be unique
      startSession(sid, usr, host, optIdFile, label, new LaunchConfigSpec(configs, getVaultData(optVaultSpec)))
    }
  }

  def showSessions = {
    if (liveSessions.nonEmpty) {
      println(s"--- live SSH sessions:")
      liveSessions foreach { e =>
        val (key, session) = e
        println(s"  [${key}]: ${session.user}@${session.host} ${session.label}")
      }
    } else println("no live sessions.")
  }

  def runOnSession (promptMsg: String)(f: (RemoteLauncherSession)=>Any) = {
    if (liveSessions.nonEmpty) {
      showSessions
      ifSome(prompt(promptMsg)) { sid =>
        ifSome(liveSessions.get(sid))(f)
      }
    } else println("no live sessions.")
  }


  def inspectSession = runOnSession("choose session number to inspect: "){ session =>
    val topic = promptOrElse("enter inspection topic (default=*): ", "*")
    session.sendInspectCtrlMsg(topic)
    // wait and display response
  }

  def removeSession = runOnSession("choose session number to terminate: "){ terminateSession }

  def terminateSession(session: RemoteLauncherSession) = {
    println(s"  terminating session [${session.sid}]")
    session.sendTerminateCtrlMsg
  }

  def removeAllSessions = {
    if (liveSessions.nonEmpty) {
      liveSessions foreach { e => terminateSession(e._2) }
    } else println("no live sessions.")
  }
}