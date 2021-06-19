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

import java.io.{File, FileInputStream}
import java.net.{Inet4Address, InetAddress, NetworkInterface}
import java.util.{Arrays => JArrays}

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import gov.nasa.race._
import gov.nasa.race.common.{ConsoleUserInfoAdapter, UserInfoFactory}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigVault
import gov.nasa.race.core.RaceActorSystem
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.util.{ClassLoaderUtils, ConsoleIO, CryptUtils}
import gov.nasa.race.uom.DateTime

import scala.jdk.CollectionConverters._
import scala.collection.Seq
import scala.collection.mutable.{Set=>MutSet}

/**
 * common functions for all xxMain objects
 */
trait MainBase {

  //var logLevel: Option[String] = None

  def message(msg: String) = println(msg) // override if this is mixed into a GUI main

  // here so that it can be overridden in contexts that run embedded, i.e. are not allowed to kill the process
  def systemExit() = System.exit(0)

  def addShutdownHook(action: => Any): Thread = {
    val thread = new Thread {
      override def run(): Unit = action
    }
    Runtime.getRuntime.addShutdownHook(thread)
    thread
  }

  def removeShutdownHook (hook: Thread) = {
    try {
      Runtime.getRuntime.removeShutdownHook(hook)
    } catch {
      case isx: IllegalStateException => // ignore (shutdown already in progress)
    }
  }

  /**
   * override this to interactively control behavior
   */
  def shutDown(ras: RaceActorSystem): Unit = {
    if (!ras.terminateActors) { // try graceful termination first
      ras.kill
    }
  }

  def launch(ras: RaceActorSystem): Unit = ras.launch

  def setConsoleUserInfoFactory = UserInfoFactory.factory = Some(ConsoleUserInfoAdapter)

  //--- Config and RaceActorSystem instantiation

  def instantiateRaceActorSystems(configFiles: Seq[File], logLevel: Option[String]): Seq[RaceActorSystem] = {
    try {
      getUniverseConfigs(configFiles, logLevel).map(new RaceActorSystem(_))
    } catch {
      case cnfx: ClassNotFoundException =>
        ConsoleIO.printlnErr(s"class not found: ${cnfx.getMessage}")
        Seq.empty[RaceActorSystem]

      case t:Throwable =>
        //t.printStackTrace()
        ConsoleIO.printlnErr(t.toString)
        Seq.empty[RaceActorSystem]
    }
  }

  def getUniverseConfigs(configFiles: Seq[File], logLevel: Option[String]): Seq[Config] = {
    val configs = if (configFiles.isEmpty) { // if no config file was specified, run the generic satellite config
      Seq(ConfigFactory.load("satellite.conf"))
    } else {
      configFiles.flatMap(existingFile(_, ConfigExt)) map { f =>
        System.setProperty("race.config.path", f.getParent)
        processGlobalConfig(ConfigFactory.load(ConfigFactory.parseFile(f)))
      }
    }

    var nUniverses = -1
    configs.foldLeft(Seq.empty[Config]) { (universeConfigs, c) =>
      nUniverses += 1
      if (c.hasPath("universes")) { // we have a list of universes wrapped in "universes [..]"
        universeConfigs ++ c.getConfigList("universes").asScala.map(processUniverseConfig(_, nUniverses, logLevel))
      } else if (c.hasPath("universe")) { // a single universe, wrapped in "universe {..}"
        universeConfigs :+ processUniverseConfig(c.getConfig("universe"), nUniverses, logLevel)
      } else { // a single universe, nothing to unwrap
        universeConfigs :+ processUniverseConfig(c, nUniverses, logLevel)
      }
    }
  }

  def processGlobalConfig(conf: Config): Config = {
    ifSome(conf.getOptionalString("classpath")) { ClassLoaderUtils.extendGlobalClasspath }
    conf
  }

  def setSystemProperties(o: MainOpts): Unit = {
    ifSome(o.propertyFile) { propFile =>
      using(new FileInputStream(propFile)) { fis => System.getProperties.load(fis) }
    }

    ifSome(o.setHost.flatMap(getInetAddress)) { iaddr =>
      val hostAddr = iaddr.getHostAddress
      System.setProperty("race.host", hostAddr)
      message(s"race.host: $hostAddr")
    }

    if (System.getProperty("race.date") == null) {
      System.setProperty("race.date", DateTime.now.formatLocal_yMd_Hms)
    }

    // logging related system properties (logback)
    ifSome(o.logLevel) { level => System.setProperty("root-level", level.toString) }

    ifSome(o.logConsoleURI) { uri =>
      val (host, port) = HostPortRE.findFirstIn(uri) match {
        case Some(HostPortRE(host, p)) => (host, if (p == null) DefaultLogConsolePort else p)
        case _ => (DefaultLogConsoleHost, DefaultLogConsolePort)
      }
      System.setProperty("log.console.host", host)
      System.setProperty("log.console.port", port)
      System.setProperty("logback.configurationFile", "logback-console.xml")
    }
  }

  def getInetAddress(ifcName: String): Option[InetAddress] = {
    val ifc = NetworkInterface.getByName(ifcName)
    ifc.getInetAddresses.asScala.find(_.isInstanceOf[Inet4Address]).map(_.asInstanceOf[Inet4Address])
  }

  //--- vault initialization


  def initConfigVault (opts: MainOpts): Boolean = {
    var success: Boolean = false
    ifSome(opts.vault) { vaultFile =>
      if (opts.keyStore.isDefined) { // use the provided keystore to get the vault key
        for (
          ksFile <- opts.keyStore;
          pw <- ConsoleIO.promptPassword(s"enter password for keystore $ksFile: ");
          ks <- CryptUtils.loadKeyStore(ksFile,pw);
          alias <- opts.alias;
          key <- withSubsequent(CryptUtils.getKey(ks,alias,pw)){ JArrays.fill(pw,' ') };
          cipher <- CryptUtils.getDecryptionCipher(key)
        ) success = ConfigVault.initialize(vaultFile,cipher)

      } else { // interactive case - ask for the vault key (if needed)
        def getPass(): Option[Array[Char]] = ConsoleIO.promptPassword(s"enter password for config vault $vaultFile: ")
        success = ConfigVault.initializeInteractive(vaultFile,getPass)
      }
    } orElse {
      success = true // no vault to initialize
      None
    }
    success
  }

  //--- config manipulation

  def processUniverseConfig(conf: Config, universeNumber: Int, logLevel: Option[String]): Config = {
    addNameConfig(addRemotingConfig(addLogLevelConfig(conf, logLevel)), universeNumber)
  }

  def addNameConfig(conf: Config, universeNumber: Int): Config = {
    if (!conf.hasPath("name")) conf.withStringValue("name", s"universe-$universeNumber")
    else conf
  }

  def addRemotingConfig(conf: Config): Config = {
    val masterName = conf.getString("name")
    val remotes = MutSet.empty[String]

    conf.getOptionalConfigList("actors").foldLeft(conf) { (universeConf, actorConf) =>
      if (actorConf.hasPath("remote")) {
        var confʹ = universeConf
        val actorName = actorConf.getString("name")
        val remoteUri = actorConf.getString("remote")
        val v = ConfigValueFactory.fromAnyRef(remoteUri)
        addIfAbsent(remotes, remoteUri) { // add the remote master too
          val remoteMasterName = userInUrl(remoteUri).get
          confʹ = confʹ.withValue(s"""akka.actor.deployment."/$masterName/$remoteMasterName".remote""", v)
        }
        confʹ.withValue(s"""akka.actor.deployment."/$masterName/$actorName".remote""", v)
      } else universeConf
    }
  }

  def addLogLevelConfig(conf: Config, logLevel: Option[String]): Config = {
    if (logLevel.isDefined) conf.withValue("akka.loglevel", ConfigValueFactory.fromAnyRef(logLevel.get))
    else conf
  }
}
