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
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.NetUtils

/**
  * command line options for RemoteLauncher
  *
  * note this is not a case class so that we can extends it. While we could keep it
  * persistent by using vals instead of vars, this is a total pain in terms of providing and
  * extending copy() functions.
  *
  * due to Scala not supporting case class inheritance, scopt options & parsers are really not
  * suitable for extension.
  */
class LauncherOpts(title: String) extends CliArgs(title) {
  var user: String = userName  // default remote user name
  var host: String = "127.0.0.1" // remote host to connect to
  var sshPort: Int = 22 // port to use for SSH
  var remotePort: Int = DefaultRemotePort // the port for control messages tunneled through SSH
  var timeout: Int = 5000 // connection timeout in msec
  var identityDir: File = new File(s"$userHome/.ssh") // directory where to look for identity files
  var identity: Option[File] = identityInit // priv key file for pubkey authentication
  var knownHosts: File = new File(userHome + "/.ssh/known_hosts") // known_hosts file to use
  var rForwards: Seq[String] = Seq.empty // reverse port forwards (list of <port>:<host>:<port> specs)
  var logLevel: Option[String] = None // remote log level to set
  var showOutput: Boolean = true // show remote output in console?
  var logFile: Option[File] = None // log remote output to file
  var configDir: File = new File(s"$userDir/config") // where to look for config files
  var remoteCmd: String = s"$userDir/script/remoterace" // remote command to run
  var keyStore: Option[File] = keystoreInit // optional keystore file for vault encryption

  opt0("--quiet")("do not print remote output on console"){showOutput=false}
  opt1("--user")("<userName>","remote user name (default=current user)"){a=> user=a}
  opt1("--host")("<hostName>","remote host (name or ip address, default=localhost)"){a=> host=a}
  opt1("--ssh-port")("<portNumber>",s"port for ssh session (default=${sshPort}"){a=> sshPort=parseInt(a)}
  opt1("--remote-port")("<portNumber>",s"(tunneled) port for remote protocol (default=$remotePort)"){a=> remotePort=parseInt(a)}
  opt1("--timeout")("<milliseconds>",s"ssh socket timeout in milliseconds (default=$timeout)"){a=> timeout=parseInt(a)}
  opt1("--rfwd")("<port>:<host>:<port>","reverse port forwarding (default=no forwarding)"){a=>
    rForwards = rForwards :+ parseSSHForward(a)
  }
  opt1("--loglevel")("<level>","remote loglevel: off,error,warning,info,debug (default=warning)"){a=> logLevel=Some(a)}
  opt1("--logfile")("<pathName>",s"logfile pathname (default=${logFile.toString})"){a=> logFile=parseFileOption(a)}
  opt1("--known-hosts")("<pathName>",s"SSH known_hosts file to use (default=${knownHosts}"){a=> knownHosts=parseExistingFile(a)}
  opt1("--identity-dir")("<pathName>",s"base directory where to look for SSH private keys (default=$identityDir)"){ a =>
    identityDir = parseExistingDir(a)
  }
  keystoreOpt // can be redefined by derived classes
  opt1("--identity")("<pathName>",s"SSH private key file to use for pubkey authentication (default=$identity)") { a =>
    identity = parseExistingFileOption(a)
  }
  opt1("--config-dir")("<pathName>",s"base directory where to look for config files (default=$configDir)") { a =>
    configDir = parseExistingDir(a)
  }

  argN("<remote-command>", s"command to run remotely (default=$remoteCmd)"){ as => remoteCmd = as.mkString(" ") }

  protected def identityInit: Option[File] = None
  protected def keystoreInit: Option[File] = None
  protected def keystoreOpt =
    opt1("--keystore")("<pathName>", s"keystore for secure ui-port connection and vault encryption(default=$keyStore)") { a =>
      keyStore = parseExistingFileOption(a)
    }

  def parseSSHForward(a: String): String = {
    a match {
      case NetUtils.PortHostPortRE(_,_,_) => a
      case other => invalid(s"invalid port forwarding spec: $a")
    }
  }

  def show = {
    println("RemoteLauncher options:")
    println(s"  user:         $user")
    println(s"  identityDir:  $identityDir")
    if (identity.isDefined) println(s"  identity:     ${identity.get}")
    println(s"  host:         $host")
    println(s"  port:         $sshPort")
    println(s"  remotePort:   $remotePort")
    println(s"  knownHosts:   $knownHosts")
    if (rForwards.nonEmpty) println(s"  rForwards:    ${rForwards.mkString(",")}")
    if (logLevel.isDefined) println(s"  logLevel:     ${logLevel.get}")
    println(s"  showOutput:   $showOutput")
    if (logFile.isDefined) println(s"  logFile:      $logFile")
    println(s"  configDir:    $configDir")
    println(s"  remote cmd:   $remoteCmd")
  }
}



//--- launcher opts for server/client

class SSLLauncherOpts(title: String) extends LauncherOpts(title) {
  var requestPort: Int = 9192

  opt1("--request-port")("<portNumber>",s"user interface port (default=$requestPort"){a=> requestPort=parseInt(a)}

  override def keystoreInit = Some(new File("race.ks"))
  override def keystoreOpt =
    requiredOpt1("--keystore")("<pathName>", s"keystore for secure ui-port connection and vault encryption(default=$keyStore)") { a =>
      keyStore = parseExistingFileOption(a)
    }

  override def show = {
    super.show
    println(s"  requestPort:  $requestPort")
  }
}
