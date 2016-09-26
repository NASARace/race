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

package gov.nasa.race.remote

import java.io.File

import gov.nasa.race.common.CliArgUtils.{OptionChecker, ParseableOption}
import gov.nasa.race.common._
import gov.nasa.race.common.NetUtils._
import gov.nasa.race.common.FileUtils._
import scopt.OptionParser

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
class LauncherOpts (var user: String = userName, /** default remote user name */
                    var host: String = "127.0.0.1", /** remote host to connect to */
                    var sshPort: Int = 22, // port to use for SSH
                    var remotePort: Int = DefaultRemotePort, // the port for control messages tunneled through SSH
                    var timeout: Int = 5000, // connection timeout in msec
                    var identityDir: File = new File(s"$userHome/.ssh"), // directory where to look for identity files
                    var identity: Option[File] = None, // priv key file for pubkey authentication
                    var knownHosts: File = new File(userHome + "/.ssh/known_hosts"), // known_hosts file to use
                    var rForwards: Seq[String] = Seq.empty, // reverse port forwards (list of <port>:<host>:<port> specs)
                    var logLevel: Option[String] = None, // remote log level to set
                    var showOutput: Boolean = true, // show remote output in console?
                    var logFile: Option[File] = None, // log remote output to file
                    var configDir: File = new File(s"$userDir/config"), // where to look for config files
                    var remoteCmd: String = s"$userDir/script/remoterace", // remote command to run
                    var keyStore: Option[File] = None // optional keystore file for vault encryption
                   ) extends ParseableOption[LauncherOpts] {
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

  override def parser = new LauncherOptsParser[LauncherOpts]("launch")

  class LauncherOptsParser [+T<:LauncherOpts](name: String) extends OptionParser[LauncherOpts](name) with OptionChecker {
    help("help") abbr ("h") text ("print this help")
    opt[String]("user") text "remote user name (default=current user)" optional () action { (v, opts) => user = v; opts }
    opt[String]("host") text "remote host (name or ip address, default=localhost)" optional () action { (v, opts) => host = v; opts }
    opt[Int]("ssh-port") text "port for ssh session (default=22)" optional () action { (v, opts) => sshPort = v; opts }
    opt[Int]("remote-port") text s"(tunneled) port for remote protocol (default=$DefaultRemotePort)" optional () action { (v, opts) => remotePort = v; opts }
    opt[Int]("timeout") text "ssh socket timeout in milliseconds (default=5000)" optional () action { (v, opts) => timeout = v; opts }
    opt[String]("rfwd") text "reverse port forwarding (default=no forwarding)" valueName "<port>:<host>:<port>,.." optional () unbounded () action {
      (v, opts) => rForwards = rForwards :+ v; opts
    } validate checkForwards
    opt[String]("loglevel") text "remote loglevel (off,error,>warning,info,debug)" optional () action { (v, opts) => logLevel = Some(v); opts }
    opt[Boolean]("show") text "print remote output on console (default=true)" optional () action { (v, opts) => showOutput = v; opts }
    opt[File]("logfile") text "logfile pathname (default=no logfile)" optional () action { (v, opts) => logFile = Some(v); opts }
    opt[File]("known-hosts") text "SSH known_hosts file to use (default=~/.ssh/known_hosts" optional () action {
      (v, opts) => knownHosts = v; opts
    } validate checkFile
    opt[File]("identity-dir") text "base directory where to look for ssh private keys" optional () action {
      (v, opts) => identityDir = v; opts
    } validate checkDir
    opt[File]("identity") text s"SSH private key file to use for pubkey authentication (default=$identity)" optional () action {
      (v, opts) => identity = Some(v); opts
    } validate checkFile
    opt[File]("config-dir") text "base directory where to look for config files" optional () action { (v, opts) =>
      configDir = v; opts
    } validate checkDir
    opt[File]("keystore").text(s"keystore for secure ui-port connection and vault encryption(default=$keyStore)").optional()
      .action{(v,opts) => keyStore = Some(v); opts} validate checkFile

    // since scopt does not support spaces in option values we have to construct the cmd to support args
    arg[String]("cmd [args..]") text "optional command (and args) to run (default=remoterace)" unbounded() optional() action {
      (v, opts) => remoteCmd = if (v.charAt(0) == '+') s"$remoteCmd $v" else v; opts
    }
  }
}



//--- launcher opts for server/client

class SSLLauncherOpts(var requestPort: Int = 9192) extends LauncherOpts(keyStore=Some(new File("race.ks"))) {
  override def show = {
    super.show
    println(s"  requestPort:  $requestPort")
  }

  override def check: Boolean = {
    checkExistingFileOption(keyStore, "keystore file") // we need a keystore for ssh key negotiation
  }

  override def parser = new SSLLauncherOptsParser[SSLLauncherOpts]("launch")

  class SSLLauncherOptsParser[+T<:SSLLauncherOpts] (name: String) extends LauncherOptsParser[T](name) {
    head("launch", "1.0")
    opt[Int]("request-port").text(s"user interface port (default=$requestPort)").optional()
      .action{(v,opts) => requestPort = v; opts}
  }
}
