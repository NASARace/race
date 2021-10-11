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

package gov.nasa.race.actor

import akka.actor.ActorRef
import com.jcraft.jsch.{JSch, Logger}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.UserInfoFactory
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceTick
import gov.nasa.race.core._
import gov.nasa.race.util.NetUtils._

object PortForwarder {
}

/**
  * an actor that forwards ports ala "ssh -L", i.e. can map ports on remote machines
  * (gateways) into local ports
  *
  * Note this requires sync user authentication during init if we don't
  * provide credentials via (encrypted) config (which is normally not a good idea). In order to
  * avoid running into a timeout the actor config should have a timeout set
  *
  * Note also that interactive authentication is the reason why we can't automatically
  * reconnect (we don't want to store user credentials here)
  *
  * This can be avoided by using Kerberos, which might in turn require to provide explicit
  * Kerberos configuration (krb5.{kdc,realm} or krb5.{conf,login}), especially if a platform
  * uses built-in Kerberos settings that are not known to Java libraries. In case of problems
  * turn on loglevel=debug and/or set sun.security.[krb5.]debug system properties to debug.
  * A common problem is a server that still requires weak encryption algorithms that as of Java 17
  * have to be explicitly enabled in krb5.conf
  */
class PortForwarder (val config: Config) extends PeriodicRaceActor {
  implicit val client = getClass

  class JSchLoggingAdapter extends Logger {
    // Akka: debug: 4, info: 3, warning: 2, error: 1
    // JSch: debug: 0, info: 1, warn: 2, error: 3, fatal: 4

    override def isEnabled(level: Int): Boolean = {
      logLevel.asInt >= 4 - level
    }

    override def log(level: Int, message: UrlString): Unit = {
      level match {
        case Logger.ERROR | Logger.FATAL => error(message)
        case Logger.WARN => warning(message)
        case Logger.INFO => info(message)
        case Logger.DEBUG => debug(message)
      }
    }
  }

  var connectTimeout = config.getIntOrElse("connect-timeout", 5000)
  // msec between alive messages if nothing received from server
  val aliveInterval = config.getIntOrElse("alive-interval", 5000)
  // number of un-answered alive msgs before disconnect
  val aliveMaxCount = config.getIntOrElse("alive-maxcount", 4)
  val strictHostKey = config.getBooleanOrElse("strict-hostkey", true)
  val authPrefs = config.getStringOrElse("authentications", "gssapi-with-mic,publickey,keyboard-interactive,password")
  val knownHosts = config.getStringOrElse( "known-hosts", System.getProperty("user.home") + "/.ssh/known_hosts")

  val user = config.getVaultableStringOrElse("user", System.getProperty("user.name"))
  val host = config.getVaultableString("host")
  val forwardL = config.getOptionalVaultableString("forward")
  val forwardR = config.getOptionalVaultableString("reverse-forward")

  setSystemProperties()

  JSch.setLogger(new JSchLoggingAdapter)
  val jsch = new JSch
  jsch.setKnownHosts( knownHosts)

  val session = jsch.getSession(user, host)

  if (forwardL.nonEmpty || forwardR.nonEmpty) {
    session.setDaemonThread(true)
    session.setServerAliveCountMax(aliveMaxCount)
    session.setServerAliveInterval(aliveInterval)

    // avoid the Kerberos double prompts
    session.setConfig("PreferredAuthentications", authPrefs)
    // shall we bypass confirmation for non-authenticating hosts
    if (!strictHostKey) session.setConfig("StrictHostKeyChecking", "no")

    info(s"$name connecting as $user@$host ..")
    ifSome(config.getOptionalString("pw")) { session.setPassword }
    ifSome(UserInfoFactory.factory) { f =>
      val ui = f.getUserInfo
      session.setUserInfo(ui)
      ui.showMessage(s"port forwarder connecting as $user@$host\n")
    }

    session.connect(connectTimeout)

    if (session.isConnected) {
      ifSome(forwardL){setPortForward(_,"forward", (lp,h,rp)=> session.setPortForwardingL(lp,h,rp))}
      ifSome(forwardR){setPortForward(_,"reverse forward", (lp,h,rp)=> session.setPortForwardingR(lp,h,rp))}

      startScheduler
      info("connected and forwarding")
    } else failDuringConstruction(s"failed to connect as $user@$host")
  } else  failDuringConstruction("no forwards specified")

  def setSystemProperties (): Unit = {
    // if there is no krb5.conf in one of the usual places (/etc) then login with Java's Kerberos GSS API will fail
    //     (see https://docs.oracle.com/en/java/javase/16/security/kerberos-requirements.html)
    // Check if there are application configured fallbacks and set system properties accordingly
    // NOTE - if there is no standard krb5.conf then these overrides should come from the vault for added security
    config.getOptionalVaultableString("krb5.kdc").foreach( s=> System.setProperty("java.security.krb5.kdc", s))
    config.getOptionalVaultableString("krb5.realm").foreach( s=> System.setProperty("java.security.krb5.realm", s))

    config.getOptionalVaultableString("krb5.login").foreach ( s=> System.setProperty("java.security.auth.login.config", s))
    config.getOptionalVaultableString("krb5.conf").foreach( s=> System.setProperty("java.security.krb5.conf", s))

    // set these sysprops from the command line to debug
    //System.setProperty("sun.security.krb5.debug", "true")
    //System.setProperty("java.security.debug", "gssloginconfig,configfile,configparser,logincontext")
  }

  def setPortForward (spec: String, action: String, f: (Int,String,Int)=>Unit) = {
    spec.split("[,; ]+").foreach {
        case PortHostPortRE(lport, host, rport) =>
          f(lport.toInt,host,rport.toInt)
          info(s"$name $action $lport:$host:$rport")
        case other => failDuringConstruction(s"$name invalid forward spec: $other")
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    if (session.isConnected) {
      session.disconnect()
      info(s"stopped port forwarding to $host")
    }
    super.onTerminateRaceActor(originator)
  }

  override def onRaceTick(): Unit = {
    checkConnected
  }

  def checkConnected = {
    if (!session.isConnected) {
      commitSuicide(s"detected lost connection to $host, committing suicide")
    }
  }
}
