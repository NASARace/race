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

import java.io._
import java.util
import javax.net.ssl.SSLContext

import gov.nasa.race._
import gov.nasa.race.common.ManagedResource
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.util.{ConsoleIO, CryptUtils, NetUtils}


/**
  * simple user interface front end for the RemoteLauncherServer, mostly for testing purposes.
  *
  * Note that we don't store sessions but rely on the RemoteLauncherDaemon to keep them. We only
  * provide session ids for our queries
  */
object ConsoleLauncherClient extends ConsoleLaunchPrompter {

  val opts: SSLLauncherOpts = new SSLLauncherOpts("ssl-launcher")

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (
        pw <- ConsoleIO.promptPassword(s"enter password for keystore ${opts.keyStore}: ");
        ksFile <- opts.keyStore;
        ks <- CryptUtils.loadKeyStore(ksFile,pw);
        keyMap <- CryptUtils.getKeyMap(ks,pw);
        kmf <- tryFinally(NetUtils.keyManagerFactory(ks,pw)){util.Arrays.fill(pw,' ')};  // done with pw - erase
        sslContext <- trySome(NetUtils.sslContext(kmf,NetUtils.trustAllCerts))
      ) {
        menu("enter command [1:add session, 2: list sessions, 3:inspect session, 4:remove session, 5: remove all, 9:exit]:\n") {
          case "1" | "add" => addSession(sslContext); repeatMenu
          case "2" | "list" => listSessions(sslContext); repeatMenu
          case "3" | "inspect session" => inspectSession(sslContext); repeatMenu
          case "4" | "terminate session" => terminateSession(sslContext); repeatMenu
          case "5" | "terminate all sessions" => terminateAllSessions(sslContext); repeatMenu
          case "9" | "exit" => println("terminating remote launcher client.")
        }
      }
    }
  }

  //--- the commands

  def addSession(sslContext: SSLContext) = {
    for (
      configFile <- promptConfigFile(opts.configDir.getPath);
      optVaultFile <- Some(promptOptionalVaultFile("."));
      label <- promptSessionLabel(s"${relPath(opts.configDir, configFile)}")
    ) sendAndPrintResponse(sslContext) {
      sendLaunchRequest(_, opts.user, configFile, optVaultFile, label)
    }
  }

  def listSessions(sslContext: SSLContext) = sendAndPrintResponse(sslContext){
    _.write(s"""list: {user:"${opts.user}"}""")
  }

  def sessionRequest(sslContext: SSLContext, op: String) = sendAndPrintResponse(sslContext) { w =>
    for (
      n <- promptInt(s"enter session number to $op: ")
    ) w.write(s"""$op: {sid:"${opts.user}-$n"}""")
  }

  def inspectSession(sslContext: SSLContext) = sessionRequest(sslContext,"inspect")

  def terminateSession(sslContext: SSLContext) = sessionRequest(sslContext,"terminate")

  def terminateAllSessions(sslContext: SSLContext) = sendAndPrintResponse(sslContext) {
    _.write(s"""terminate-all: {user:"${opts.user}"}""")
  }

  //--- helper functions

  def sendAndPrintResponse(sslContext: SSLContext)(sendAction: (Writer)=>Unit) = {
    for (
      sock <- ManagedResource.ensureClose(NetUtils.sslSocket(sslContext,opts.host,opts.requestPort));
      writer <- NetUtils.createWriter(sock);
      reader <- NetUtils.createReader(sock)
    ){
      sendAction(writer)
      writer.flush
      printResponse(reader)
    }
  }

  def printResponse (reader: Reader) = {
    NetUtils.readAll(reader) match {
      case Some(resp) => println(resp)
      case None => println("no response from server")
    }
  }

  def sendLaunchRequest(writer: Writer, usr: String, configFile: File, optVaultFile: Option[File], label: String): Unit = {
    writer.write("launch: {")
    writer.write(s"""user:"$usr"""")
    writer.write(s""",config:"${configFile.getPath}"""")
    ifSome(optVaultFile) { vaultFile => writer.write(s""",vault:${vaultFile.getPath}"""") }
    writer.write(s""",label:"$label"}""")
  }
}
