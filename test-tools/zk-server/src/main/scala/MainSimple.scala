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

package gov.nasa.race.zkserver

import java.io.{PrintStream, FileOutputStream, File}
import gov.nasa.race.common.ConsoleIO._
import org.apache.zookeeper.server.quorum._
import scopt.OptionParser

/**
 * driver to run a Zookeeper server from the command line
 *
 * Note that although this could make perfect use of WrappedApp, we don't
 * use it so that we can use WrappedApp in integration tests using Zookeeper
 */

object MainSimple {

  final val ZK_DIR = "tmp/zk"
  final val PORT = 2181
  final val TICKTIME = 2000
  final val INIT_LIMIT = 10
  final val SYNC_LIMIT = 5
  final val LOGGER = "INFO,CONSOLE"

  final val CONF_FILE = "zoo.conf"

  case class CliOpts (dir: String=ZK_DIR,
                      port: Int=PORT,
                      tickTime: Int=TICKTIME,
                      initLimit: Int=INIT_LIMIT,
                      syncLimit: Int=SYNC_LIMIT,
                      logger: String=LOGGER)

  def cliParser = {
    new OptionParser[CliOpts]("zkserver") {
      help("help") abbr ("h") text ("print this help")
      opt[String]('d', "dir") text ("root data directory") optional() action {(v, o) => o.copy(dir = v)}
      opt[Int]('p', "port") text ("server port to connect to") optional() action {(v, o) => o.copy(port = v)}
      opt[Int]("tick-time") text ("number of msec per tick") optional() action {(v, o) => o.copy(tickTime = v)}
      opt[Int]("init-limit") text ("max ticks for init phase") optional() action {(v, o) => o.copy(initLimit = v)}
      opt[Int]("sync-limit") text ("max ticks between req/ack") optional() action {(v, o) => o.copy(syncLimit = v)}
      opt[String]("logger") text ("logger settings") optional() action {(v, o) => o.copy(logger = v)}
    }
  }

  def writeConf (dir: File, opts: CliOpts): File = {
    val zooCfg = new File(dir, CONF_FILE)

    val ps = new PrintStream( new FileOutputStream(zooCfg))
    ps.println("# automatically generated Zookeeper configuration file")
    ps.println(s"dataDir=$dir")
    ps.println(s"clientPort=${opts.port}")
    //ps.println(s"tickTime=${opts.tickTime}")
    //ps.println(s"initLimit=${opts.initLimit}")
    //ps.println(s"syncLimit=${opts.syncLimit}")
    ps.println("maxClientCnxns=0")
    ps.close()

    zooCfg
  }

  def setSystemProperties (opts: CliOpts) = {
    val dpath = opts.dir + '/'
    System.setProperty("zookeeper.log.dir", dpath + "logs")
    System.setProperty("zookeeper.log.file", "")
    System.setProperty("zookeeper.root.logger", opts.logger)
    System.setProperty("zookeeper.jmx.log4j.disable", "true")
    //System.setProperty("com.sun.management.jmxremote", "")
    //System.setProperty("com.sun.management.jmxremote.local.only", "true")
  }

  def main (args: Array[String]): Unit = {
    System.setProperty("logback.configurationFile", "logback-zk.xml") // need our own to not collide with race

    val cliOpts: CliOpts = cliParser.parse(args, CliOpts()).get

    val dir = new File(cliOpts.dir)
    if (!dir.isDirectory ){
      if (!dir.mkdirs){
        println("ERROR - could not create Zookeeper data directory, exiting")
        return
      }
    }

    // writing a temp config file from command line arguments seems convoluted,
    // but unfortunately Zookeeper doesn't export a startup API, and it would
    // be too fragile if we use its internals
    val conf = writeConf(dir, cliOpts)
    if (!conf.isFile){
      println(s"ERROR - could not create Zookeeper config file $conf, exiting")
      return
    }

    setSystemProperties(cliOpts)

    val zkThread = new Thread {
      override def run: Unit = {
        QuorumPeerMain.main(Array(conf.getPath))
      }
    }
    zkThread.start()
    Thread.sleep(500)

    menu("enter command [9:exit]:\n") {
      case "9" =>
        println("shutting down Zookeeper..")
        System.exit(0)
    }
  }
}
