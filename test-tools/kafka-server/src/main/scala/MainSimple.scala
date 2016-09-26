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
import java.util.Properties

import gov.nasa.race.common.ConsoleIO._
import kafka.admin.TopicCommand
import kafka.admin.TopicCommand.TopicCommandOptions
import kafka.metrics.KafkaMetricsReporter
import kafka.server.{KafkaConfig, KafkaServerStartable}
import kafka.utils.{ZkUtils, VerifiableProperties}
import org.apache.kafka.common.security.JaasUtils
import org.apache.log4j.PropertyConfigurator
import scopt.OptionParser

import scala.io.StdIn

/**
 * driver to run a Kafka server from the command line
 *
 * Note that although this could make perfect use of WrappedApp, we don't
 * use it so that we can use WrappedApp in integration tests using Zookeeper
 */

object MainSimple {

  final val KAFKA_DIR = "tmp/kafka"

  case class CliOpts (dir: String=null,
                      id: String=null,
                      port: String=null,
                      configFile: String=null,
                      show: Boolean=false)

  def cliParser = {
    new OptionParser[CliOpts]("kafkaserver") {
      help("help") abbr ("h") text ("print this help")
      opt[String]('d', "dir") text ("root data directory") optional() action {(v, o) => o.copy(dir = v)}
      opt[String]('c', "config") text ("config file") optional() action {(v, o) => o.copy(configFile = v)}
      opt[String]('p', "port") text ("server port") optional() action {(v, o) => o.copy(port = v)}
      opt[String]('i', "id") text ("server id") optional() action {(v, o) => o.copy(id = v)}
      opt[Unit]('s', "show") text ("show server properties") optional() action {(_, o) => o.copy(show=true)}
    }
  }

  def getKafkaProperties (opts: CliOpts): Properties = {
    val props = new Properties
    val is = if (opts.configFile != null) new FileInputStream(opts.configFile)
             else getClass().getResourceAsStream("/server.properties")
    props.load(is)
    is.close()

    // mix in command line overrides
    setOptionalProp(props, "log.dirs", opts.dir)
    setOptionalProp(props, "broker.id", opts.id)
    setOptionalProp(props, "port", opts.port)

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

  def getLog4jProperties: Properties = {
    val props = new Properties
    val is =  getClass().getResourceAsStream("/log4j.properties")
    props.load(is)
    is.close()
    props
  }

  def setSystemProperties (opts: CliOpts) = {
    System.setProperty("com.sun.management.jmxremote", "")
    //System.setProperty("com.sun.management.jmxremote.port", "3333")
    //System.setProperty("com.sun.management.jmxremote.authenticate", "false")
    //System.setProperty("com.sun.management.jmxremote.ssl", "false")
    System.setProperty("com.sun.management.jmxremote.local.only", "true")

    System.setProperty("kafka.logs.dir", "tmp/kafka-logs")

    //System.setProperty("log4j.configuration", "jar:file:/gov/nasa/race/kafka/log4j.properties")
    //System.setProperty("log4j.debug", "true")
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

    val cliOpts: CliOpts = cliParser.parse(args, CliOpts()).get

    val props = getKafkaProperties(cliOpts)
    checkLogDirs(props)
    setSystemProperties(cliOpts)
    PropertyConfigurator.configure(getLog4jProperties)

    val thread = new Thread {
      override def run: Unit = {
        val serverConfig = new KafkaConfig(props)
        //KafkaMetricsReporter.startReporters(serverConfig.props) // 0.8.x
        KafkaMetricsReporter.startReporters(new VerifiableProperties(props)) // 0.9 ?? doesn't work
        val kafkaServerStartable = new KafkaServerStartable(serverConfig)

        // attach shutdown handler to catch control-c
        Runtime.getRuntime().addShutdownHook(new Thread() {
          override def run() = {
            kafkaServerStartable.shutdown
          }
        })

        kafkaServerStartable.startup
        kafkaServerStartable.awaitShutdown
      }
    }
    thread.start()
    Thread.sleep(500)

    val zkUtils = ZkUtils(props.get("zookeeper.connect").toString, 30000, 30000, false)

    menu("enter command [1:listTopics, 2:createTopic, 9:exit]:\n") {
      case "1" =>
        //TopicCommand.main(Array("--list", "--zookeeper", props.get("zookeeper.connect").toString))
        TopicCommand.listTopics(zkUtils,new TopicCommandOptions(Array[String]()))
        repeatMenu

      case "2" =>
        val topic = StdIn.readLine("  enter topic name: ")
        if (topic != "") {
          var partitions = StdIn.readLine("  enter number of partitions (default=1): ")
          if (partitions == "") partitions = "1"

          var replicationFactor = StdIn.readLine("  enter replication factor (default=1): ")
          if (replicationFactor == "") replicationFactor = "1"

          val args = Array("--replication-factor", replicationFactor,
                           "--partitions", partitions,
                           "--topic", topic)
          TopicCommand.createTopic(zkUtils, new TopicCommandOptions(args))
        }
        repeatMenu
      case "9" =>
        println("shutting down Kafka..")
        System.exit(0)
    }
  }
}
