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
import java.net.{ServerSocket, Socket}

import com.jcraft.jsch.JSch
import gov.nasa.race._
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.main.MainBase
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.util.ThreadUtils._
import gov.nasa.race.util.{ClassLoaderUtils, JarSpec, NullOutputStream, TeeOutputStream}

import scala.collection.immutable.ListMap

object RemoteLauncher {
   val codeSourceEntries = Seq(
    "com.jcraft.jsch.JSch" -> JarSpec("/com.jcraft.jsch-0.1.54.jar", "da3584329a263616e277e15462b387addd1b208d")
    // <2do> once we have a ueber-jar for RACE, we should add UserInterfaceFactory and respective classes
  )
}

/**
 * common base for applications that launch and communicate with RemoteMain instances over
 * the network. Each session is alive as long as the remote process is running.
 * There are 4 basic requirements for the underlying mechanism, which make ssh a good match:
 *
 *  - securely start remote processes, including (login-free, token-based) user authentication
 *    and (CA based) host verification
 *  - communicate through a secure channel with the remote process, preferably through a single
 *    public (firewall-visible) port
 *  - tunneling - allow multiple logical data streams through this channel (e.g. stdio streams
 *    plus control streams)
 *  - monitor lifetime of remote processes
 *
 * RemoteLauncher instances can also handle global external resources such as available hosts etc.
 *
 * Note the concrete class might not run interactively, so we can't rely on user input here
 */
abstract class RemoteLauncher extends MainBase with RemoteProtocolLauncher {

  /**
   * a SSHSession that tunnels a control message socket through SSH in order to communicate
   * with RemoteMain instances
   */
  protected class RemoteLauncherSession(sid: String,  // unique session id (used as a key to retrieve this session)
                                        user: String, // remote user
                                        host: String, // remote host to run on
                                        identity: Option[File], // optional private key file for ssh authentication
                                        sshPort: Int, // the port the remote ssh server is listening on
                                        cmd: String,  // the command to run on the remote server
                                        label: String // a mnemonic for listing sessions
                                       ) extends SSHSession(jsch, sid, user, host, identity, sshPort, cmd, label) {

    var remotePid: String = _ // to be set from RemoteMain LIVE message

    var remoteSocket: Option[Socket] = None
    var remoteProtoReader: BufferedReader = _
    var remoteProtoOut: OutputStream = _

    def openControlChannel(ssock: ServerSocket) = {
      // only forward if we are not running on the same machine
      val remotePort = ssock.getLocalPort
      if (!isLocalhost(host)) session.setPortForwardingR(remotePort, "127.0.0.1", remotePort)

      val ctrlSock = ssock.accept() // this is blocking and can timeout
      remoteSocket = Some(ctrlSock)
      remoteProtoReader = new BufferedReader(new InputStreamReader(ctrlSock.getInputStream))
      remoteProtoOut = ctrlSock.getOutputStream
    }

    def sendSessionTokenCtrlMsg = sendSessionToken(remoteProtoOut, System.currentTimeMillis().toHexString)

    def waitForLiveCtrlMsg = ifSome(readLiveMessage(remoteProtoReader, opts.timeout)) { remotePid = _ }.isDefined

    def sendLaunchCtrlMsg(ld: LaunchConfigSpec): Unit = sendLaunchCmd(remoteProtoOut, serializeBase64(ld))

    def sendInspectCtrlMsg(topic: String) = sendInspectCmd(remoteProtoOut, topic)

    def sendTerminateCtrlMsg = sendTerminationCmd(remoteProtoOut)

    override def disconnect = {
      ifSome(remoteSocket) { ctrlSock =>
        ctrlSock.close
        remoteSocket = None
      }
      super.disconnect
    }
  }

  val defaultCmd = s"$userDir/script/remoterace" // assuming this is running on the machine it was built

  ClassLoaderUtils.verifyCodeSource[JSch] // do this once so that we can trust JSch

  protected val jsch = new JSch
  protected var log: OutputStream = scala.Console.out

  protected var liveSessions = ListMap.empty[String,RemoteLauncherSession]
  protected var sessionCount = 0 // consecutive counter for created sessions (not liveSessions.size)

  protected var serverSocket: ServerSocket = _

  // those can be overridden if we have derived options
  protected val opts: LauncherOpts = new LauncherOpts("launcher")

  def initializeWithOpts = {
    // if we had cli options they are already verified by the parser
    // (we don't complain if the standard ~/.ssh/ files don't exist)
    jsch.setKnownHosts(opts.knownHosts.getAbsolutePath)
    ifSome(opts.identity) { file => jsch.addIdentity(file.getPath) }

    serverSocket = new ServerSocket(opts.remotePort)
    serverSocket.setSoTimeout(opts.timeout)
  }

  //--- standard IO that can be overridden by concrete classes
  def error(msg: String) = printlnErr(msg)
  def warning(msg: String) = printlnErr(msg)
  def info(msg: String) = println(msg)

  def initLogging = {
    if (opts.showOutput) {
      if (opts.logFile.isDefined) log = new TeeOutputStream(log, new FileOutputStream(opts.logFile.get))
    } else {
      log = if (opts.logFile.isDefined) new TeeOutputStream(new FileOutputStream(opts.logFile.get)) else NullOutputStream
    }
  }

  def installShutdownHook = {
    val thread = new Thread {
      override def run(): Unit = {
        monitorThread.terminate()
        liveSessions.foreach( e => e._2.disconnect)
        log.flush()
        log.close()
      }
    }
    Runtime.getRuntime.addShutdownHook(thread)
  }

  def checkForTerminatedSessions = synchronized {
    // since session termination is rare compared to checks, we only create a new list if there was a termination
    val terminated = liveSessions.filterNot( e => e._2.isAlive)
    if (terminated.nonEmpty) liveSessions = liveSessions -- terminated.keys
  }
  // note that we use the default finite blocking timeout when there are no liveThreads,
  // otherwise we could get deadlocks due to missed signals
  protected val monitorThread = new MonitorThread()(liveSessions.nonEmpty)(checkForTerminatedSessions)

  def startMonitoringSessions = monitorThread.start
  def stopMonitoringSessions = monitorThread.terminate()

  def incSessionCount: Int = synchronized {
    sessionCount += 1
    sessionCount
  }

  def startSession(sid: String, usr: String, host: String, identity: Option[File], label: String,
                   configSpec: LaunchConfigSpec): Option[RemoteLauncherSession] = {
    val session = new RemoteLauncherSession(sid, usr, host, identity, opts.sshPort, opts.remoteCmd, label)
    try {
      session.connect
      session.spawnStdioThreads(log)
      session.openControlChannel(serverSocket)

      session.sendSessionTokenCtrlMsg
      if (session.waitForLiveCtrlMsg) { // this blocks until RemoteMain is up or runs into a timeout
        info(s"[${session.sid}] session alive: ${session.remotePid}")
        synchronized {
          liveSessions = liveSessions + (session.sid -> session)
          if (liveSessions.size == 1) monitorThread.wakeUp()
        }
        session.sendLaunchCtrlMsg(configSpec)
        Some(session)

      } else {
        error(s"[${session.sid}] session failed to send LIVE message, disconnecting")
        session.disconnect
        None
      }
    } catch {
      case x: Throwable =>
        error(s"[${session.sid}] session connection failed with: $x")
        if (session.isAlive) session.disconnect
        None
    }
  }

  def launcherReady = {
    opts.show
    info("remote launcher ready.")
  }
}
