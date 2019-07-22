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
import com.jcraft.jsch.{Channel, ChannelExec, JSch}
import gov.nasa.race._
import gov.nasa.race.common.UserInfoFactory
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.uom.DateTime

/**
 * class that represents a Jsch ssh session at runtime, including
 * all the data that is required to communicate with the external process.
 *
 * Note that we don't make any assumptions reg. the remote process type here, i.e.
 * SSHSession instances could be used for any remote process, not just RemoteMain
 */
class SSHSession(val jsch: JSch, val sid: String,
                 val user: String, val host: String, val identity: Option[File], val port: Int,
                 val cmd: String, val label: String) {
  val session = jsch.getSession(user, host, port)
  val startDate = DateTime.now

  protected var channel: Option[Channel] = None

  // only defined if channel is connected
  protected var remoteOut: InputStream = _
  protected var remoteErr: InputStream = _
  protected var remoteIn: OutputStream = _

  ifSome(identity) { file => jsch.addIdentity(file.getPath) } // <2do> check if this causes duplicate entries
  session.setDaemonThread(true)

  def connect = {
    UserInfoFactory.factory.foreach { f => session.setUserInfo(f.getUserInfo) }

    session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
    session.setConfig("NoHostAuthenticationForLocalhost", "yes")
    session.setConfig("ForwardX11", "no")
    // <2do> do we need to set "IdentityFile" here?
    session.connect

    if (session.isConnected) connectChannel
  }

  def reverseForward(forwardSpec: String) = {
    forwardSpec match {
      case PortHostPortRE(lport, host, rport) => session.setPortForwardingR(lport.toInt, host, rport.toInt)
      case other => // ignore
    }
  }

  def connectChannel = {
    val chan = session.openChannel("exec").asInstanceOf[ChannelExec]
    channel = Some(chan)

    chan.setCommand(cmd)
    remoteIn = chan.getOutputStream
    remoteOut = chan.getInputStream
    remoteErr = chan.getErrStream

    chan.connect()
    //chan.connect(timeout)
  }

  def isAlive = session.isConnected && withSomeOrElse(channel, false)(_.isConnected)
  def exitStatus = withSomeOrElse(channel, -1)(_.getExitStatus)

  // send a text line to the remoteIn stream. The '\n' is automatically added here
  def sendLine(line: String) = {
    if (channel.isDefined) {
      remoteIn.write(line.getBytes)
      remoteIn.write('\n')
      remoteIn.flush()
    }
  }

  private def spawnRemoteOutputThread(is: InputStream, log: OutputStream) = {
    val thread = new Thread {
      setDaemon(true)
      val buf = new Array[Byte](1024)
      override def run: Unit = {
        try {
          var n = is.read(buf) // this blocks until we got input, or the stream is closed
          while (n > 0) {
            log.write(buf, 0, n)
            n = is.read(buf)
          }
        } catch {
          case x: IOException => // ignore, pipe was closed
        }
      }
    }
    thread.start
  }

  def spawnStdioThreads(log: OutputStream) = {
    if (channel.isDefined) {
      spawnRemoteOutputThread(remoteOut, log)
      spawnRemoteOutputThread(remoteErr, log)
    }
  }

  def disconnect: Unit = {
    ifSome(channel) { chan =>
      if (chan.isConnected) {
        remoteIn.close()
        remoteOut.close()
        remoteErr.close()
        chan.disconnect()
      }
      channel = None
    }
    if (session.isConnected) {
      session.disconnect
    }
  }
}
