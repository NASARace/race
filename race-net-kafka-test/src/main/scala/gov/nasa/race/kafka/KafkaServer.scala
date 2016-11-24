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

package gov.nasa.race.kafka

import java.io.{File, FileInputStream}
import java.net.InetSocketAddress
import java.util.Properties

import kafka.admin.TopicCommand
import kafka.admin.TopicCommand.TopicCommandOptions
import kafka.metrics.KafkaMetricsReporter
import kafka.server.{KafkaConfig,KafkaServerStartable}
import kafka.utils.{VerifiableProperties, ZkUtils}
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}

import gov.nasa.race._ // NOTE - has to come *after* kafka imports or it will define a .race.kafka package that makes it
import scala.io.StdIn
import gov.nasa.race.main.CliArgs
import gov.nasa.race.main.ConsoleIO._
import gov.nasa.race.util.FileUtils
/**
  * driver to run a Kafka server plus embedded Zookeeper server from the command line
  *
  * for test purposes only
  */

object KafkaServer {

  final val KAFKA_DIR = "tmp/kafka"
  final val ZK_DIR = "tmp/zk"

  class Opts extends CliArgs ("kafkaserver") {
    var configFile: Option[File] = None
    var dir: Option[File] = None
    var clean: Boolean = false
    var port: Option[Int] = None
    var id: Option[String] = None
    var topic: Option[String] = None
    var show = false

    opt1("--config")("<pathName>",s"config file (default=${configFile.toString}"){ a=> configFile = parseExistingFileOption(a)}
    opt1("-d","--dir")("<pathName>","data directory") { a=> dir = parseDirOption(a) }
    opt0("-c","--clean")(s"clean data before/after running"){ clean = true }
    opt1("-p", "--port")("<portNumber>","server port") { a=> port = Some(parseInt(a))}
    opt1("--id")("<name>","server id") { a=> id = Some(a) }
    opt1("-t","--topic")( "<topicName>","create topic") { a=> topic = Some(a) }
    opt0("--show")("show server properties"){ show = true }
  }

  def getKafkaProperties (opts: Opts): Properties = {
    val props = new Properties

    val is = opts.configFile match {
      case Some(file) => new FileInputStream(file)
      case None => getClass.getResourceAsStream("/kafka-server.properties")
    }
    props.load(is)
    is.close()

    // mix in command line overrides
    ifSome(opts.dir){ d => props.put("log.dirs",d) }
    ifSome(opts.id){ name => props.put("broker.id",name)}
    ifSome(opts.port){ port => props.put("port",s"$port")}

    // check if we have a log.dirs entry
    if (props.get("log.dirs") == null) {
      props.put("log.dirs", KAFKA_DIR)
    }

    if (opts.show) {
      println(s"------- accumulated kafka server properties:")
      println(props)
      println("-------")
    }

    props
  }

  def setOptionalProp (props: Properties, k: String, v: String): Unit = {
    if (v != null){
      println(s"setting from command line $k=$v")
      props.put(k, v)
    }
  }

  def setSystemProperties (opts: Opts) = {
    System.setProperty("com.sun.management.jmxremote", "")
    //System.setProperty("com.sun.management.jmxremote.port", "3333")
    //System.setProperty("com.sun.management.jmxremote.authenticate", "false")
    //System.setProperty("com.sun.management.jmxremote.ssl", "false")
    System.setProperty("com.sun.management.jmxremote.local.only", "true")

    System.setProperty("kafka.logs.dir", "tmp/kafka-logs")
  }

  def cleanDirs = {
    println("resetting Kafka and Zookeeper dirs")
    FileUtils.deleteRecursively(new File(KAFKA_DIR))
    FileUtils.deleteRecursively(new File(ZK_DIR))
  }

  def checkLogDirs (props: Properties) = {
    // we take the first one as our kafka root dir
    for (path <- props.getProperty("log.dirs").split(",")) {
      val logDir = new File(path)
      if (!logDir.isDirectory){
        if (!logDir.mkdirs){
          println(s"ERROR - could not create Kafka data directory $path, terminating")
          System.exit(1)
        }
      }
    }
  }

  def main (args: Array[String]): Unit = {
    System.setProperty("logback.configurationFile", "logback-kafka.xml") // need our own to not collide with race
    val cliOpts = CliArgs(args)(new Opts).getOrElse(return)

    val props = getKafkaProperties(cliOpts)
    checkLogDirs(props)
    setSystemProperties(cliOpts)

    if (cliOpts.clean) cleanDirs

    //--- zookeeper
    val zkDir = new File(ZK_DIR)
    val zkServer = new ZooKeeperServer(zkDir,zkDir,2000)
    val zkFactory = new NIOServerCnxnFactory()
    zkFactory.configure(new InetSocketAddress(2181),5000)
    zkFactory.startup(zkServer)
    val zkUtils = ZkUtils(props.get("zookeeper.connect").toString, 30000, 30000, false)

    //--- Kafka
    val serverConfig = new KafkaConfig(props)
    KafkaMetricsReporter.startReporters(new VerifiableProperties(props))
    val kafkaServerStartable = new KafkaServerStartable(serverConfig)

    Runtime.getRuntime().addShutdownHook(new Thread() {
      override def run() = {
        kafkaServerStartable.shutdown
        zkFactory.shutdown
        if (cliOpts.clean) cleanDirs
      }
    })

    val thread = new Thread {
      override def run: Unit = {
        kafkaServerStartable.startup
        kafkaServerStartable.awaitShutdown

        zkFactory.shutdown
        if (cliOpts.clean) cleanDirs
      }
    }
    thread.start()
    Thread.sleep(500)

    ifSome(cliOpts.topic){ topicName => createTopic(zkUtils,topicName,"1","1")}

    menu("enter command [1:listTopics, 2:createTopic, 9:exit]:\n") {
      case "1" =>
        //TopicCommand.main(Array("--list", "--zookeeper", props.get("zookeeper.connect").toString))
        TopicCommand.listTopics(zkUtils,new TopicCommandOptions(Array[String]()))
        repeatMenu

      case "2" =>
        val topic = StdIn.readLine("  enter topic name:\n")
        if (topic != "") {
          var partitions = StdIn.readLine("  enter number of partitions (default=1):\n")
          if (partitions == "") partitions = "1"
          var replicationFactor = StdIn.readLine("  enter replication factor (default=1):\n")
          if (replicationFactor == "") replicationFactor = "1"
          createTopic(zkUtils, topic, partitions, replicationFactor)
        }
        repeatMenu
      case "9" =>
        println("shutting down Kafka..")
        kafkaServerStartable.shutdown
        //System.exit(0)
    }
  }

  def createTopic (zkUtils: ZkUtils, topic: String, partitions: String, replicationFactor: String) = {
    val args = Array("--replication-factor", replicationFactor,
      "--partitions", partitions,
      "--topic", topic)
    TopicCommand.createTopic(zkUtils, new TopicCommandOptions(args))
  }
}
