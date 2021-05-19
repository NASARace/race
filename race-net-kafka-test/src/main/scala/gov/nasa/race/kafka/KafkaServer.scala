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

import java.io.{File, FileInputStream, FileOutputStream, PrintStream}
import java.lang.Thread.State._
import java.util.Properties
import kafka.Kafka
import org.apache.kafka.common.Uuid
import kafka.tools.StorageTool
import kafka.admin.{ConsumerGroupCommand, TopicCommand}

import scala.io.StdIn
import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.util.{FileUtils, StringUtils, ThreadUtils}
import org.apache.kafka.common.utils.Exit

import scala.collection.mutable

/**
  * driver to run a simple Kafka server in Kraft mode (i.e. without Zookeeper) and provide a
  * menu to interactively control it (e.g. to add,list,delete topics)
  *
  * This is for testing purposes only, to simplify having to set up a Kafka cluster. Since this has been very sensitive
  * to Kafka changes in the past we use the respective Kafka main() methods, but within the same process. This means
  * we have to make sure the respective tools run embedded, i.e. don't terminate the JVM upon exit (Kafka has Exit
  * methods that have to be overridden for that purpose) and have a properly set up file system (config and kafka log dir).
  *
  * Since Kafka needs to have its (populated) log.dirs directory anyways there is little use trying to avoid file system
  * access for the config file, which would force us to forego calling its main() methods and rely on access to methods
  * called from within there (which has changed in the past). If there is no explicit --config option given we therefore
  * generate a default config file from class resources and put it in the kafka log dir
  *
  * Since there is always a physical kafka config file we drop overriding the dir and the bootstrap server from
  * command line options. Use the --config option to override if the defaults (tmp/kafka and localhost:9092) are not
  * suitable
  */
object KafkaServer {

  class Opts extends CliArgs ("kafkaserver") {
    var configFile: Option[File] = None
    var clean: Boolean = false
    var topic: Option[String] = None
    var show = false

    opt1("--config")("<pathName>",s"config file"){ a=> configFile = parseExistingFileOption(a)}
    opt0("-c","--clean")(s"clean data before/after running"){ clean = true }
    opt1("-t","--topic")( "<topicName>","create topic") { a=> topic = Some(a) }
    opt0("--show")("show server properties"){ show = true }
  }

  def setSystemProperties (opts: Opts) = {
    System.setProperty("com.sun.management.jmxremote", "")
    //System.setProperty("com.sun.management.jmxremote.port", "3333")
    //System.setProperty("com.sun.management.jmxremote.authenticate", "false")
    //System.setProperty("com.sun.management.jmxremote.ssl", "false")
    System.setProperty("com.sun.management.jmxremote.local.only", "true")
  }

  def getDefaultServerProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getClassLoader.getResourceAsStream("kafka-server.properties"))
    props
  }

  def getPropertiesFromFile (kConfFile: File): Properties = {
    val props = new Properties()
    props.load( new FileInputStream(kConfFile))
    props
  }

  def writePropertiesFile (file: File, props: Properties): Unit = {
    if (file.isFile) file.delete()
    props.store(new FileOutputStream(file),"generated Kafka properties")
  }

  // this parses "PLAINTEXT://localhost:9092 .." values of the server config
  val serverRE = ".*://([^, ]+)".r

  // this tries to read the bootstrapServer from the 'advertised.listeners' property of the server config
  def getBootstrapServerFromProps (props: Properties): Option[String] = {
    val adListeners = props.getProperty("advertised.listeners")
    if (adListeners != null) {
      serverRE.findFirstMatchIn(adListeners) match {
        case Some(m) => Some(m.group(1))
        case None => None
      }
    } else None
  }

  def getKafkaDirFromProps (props: Properties): Option[File] = {
    val pn = props.getProperty("log.dirs")
    if (pn != null) Some(new File(pn)) else None
  }

  def cleanDirs (kafkaDir: File) = {
    println(s"resetting Kafka dir: $kafkaDir")
    FileUtils.deleteRecursively(kafkaDir)
  }

  def main (args: Array[String]): Unit = {
    //System.setProperty("logback.configurationFile", "logback-kafka.xml") // need our own to not collide with race
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel","WARN")

    val cliOpts = CliArgs(args)(new Opts).getOrElse(return)
    setSystemProperties(cliOpts)

    val serverProps = cliOpts.configFile match {
      case Some(file) => getPropertiesFromFile(file)
      case None => getDefaultServerProperties
    }

    val kafkaDir = getKafkaDirFromProps(serverProps).getOrElse(new File("tmp/kafka"))
    val bootstrapServer = getBootstrapServerFromProps(serverProps).getOrElse("localhost:9092")

    if (cliOpts.clean) cleanDirs(kafkaDir)
    kafkaDir.mkdirs()

    val propFile = cliOpts.configFile.getOrElse {
      val file = new File(kafkaDir,"kafka-server.properties")
      writePropertiesFile(file,serverProps)
      file
    }

    // this is to prevent Kafka utils/tools main() methods to terminate the JVM
    Exit.setExitProcedure((statusCode: Int, message: String) => {})

    //--- generate cluster id
    val uuid = Uuid.randomUuid().toString

    //--- format storage dirs
    runKafkaExecutable {
      StorageTool.main( Array("format", "-t", uuid, "--config", propFile.getCanonicalPath))
    }

    //--- start server
    val serverThread = ThreadUtils.startDaemon {
      startServer(propFile, bootstrapServer)
    }

    waitForServerThread(serverThread)

    //--- (optionally) create topics
    ifSome(cliOpts.topic){ topicName => createTopic(topicName,propFile,bootstrapServer)}

    runCommandLoop(propFile, bootstrapServer)
  }

  // yet another quirk - StorageTool uses a wrapper around import org.apache.kafka.common.utils.Exit that throws an
  // AssertionError if the real Exit.exit returns (which is fully in its rights)
  def runKafkaExecutable (f: =>Unit): Unit = {
    try f
    catch {
      case x: AssertionError => // ignore, it's the kafka.utils.Exit wrapper rejecting valid kafka.common.utils.Exit behavior
        //x.printStackTrace()
    }
  }

  def startServer (configFile: File, bootstrapServer: String): Unit = {
    runKafkaExecutable {
      val args = Array(configFile.getCanonicalPath)

      println(s"Kafka started with config: ${StringUtils.mkSepString(args,',')}")
      println(s"listening on $bootstrapServer")
      Kafka.main( args) // this does not return until the server is shut down or the JVM exits
    }
  }

  def waitForServerThread (serverThread: Thread): Unit = {
    Thread.sleep(1000)
    while ( {val state = serverThread.getState; state != WAITING && state != TERMINATED}){
      Thread.sleep(1000)
    }
    if (serverThread.getState == TERMINATED) {
      println("server did terminate prematurely, exiting")
      System.exit(1)
    }
  }

  def runCommandLoop (configFile: File, bootstrapServer: String): Unit = {
    menu("enter command [1:listTopics, 2:createTopic, 3:describeTopic, 4:deleteTopic, 5:listConsumers, 9:exit]:\n") {
      case "1" =>
        listTopics(bootstrapServer)
        repeatMenu

      case "2" =>
        val topic = StdIn.readLine("  enter topic name:\n")
        if (topic != "") {
          createTopic(topic,configFile,bootstrapServer)
        }
        repeatMenu

      case "3" =>
        val topic = StdIn.readLine("  enter topic name:\n")
        if (topic != "") {
          describeTopic(topic, bootstrapServer)
        }
        repeatMenu

      case "4" =>
        val topic = StdIn.readLine("  enter topic name:\n")
        if (topic != "") {
          deleteTopic(topic, bootstrapServer)
        }
        repeatMenu

      case "5" =>
        listConsumers(bootstrapServer)
        repeatMenu

      //.. and possibly more

      case "9" =>
        println("shutting down Kafka..")
        System.exit(0)
    }
  }

  def createTopic (topic: String, configFile: File, bootstrapServer: String): Unit = {
    runKafkaExecutable {
      TopicCommand.main( Array(
        "--create",
        "--topic", topic,
        "--bootstrap-server", bootstrapServer
      ))
    }
  }

  def listTopics (bootstrapServer: String): Unit = {
    runKafkaExecutable {
      TopicCommand.main( Array(
        "--list",
        "--bootstrap-server", bootstrapServer
      ))
    }
  }

  def describeTopic (topic: String, bootstrapServer: String): Unit = {
    runKafkaExecutable {
      TopicCommand.main(Array(
        "--describe",
        "--topic", topic,
        "--bootstrap-server", bootstrapServer
      ))
    }
  }

  def deleteTopic (topic: String, bootstrapServer: String): Unit = {
    runKafkaExecutable {
      TopicCommand.main(Array(
        "--delete",
        "--topic", topic,
        "--bootstrap-server", bootstrapServer
      ))
    }
  }

  def listConsumers (bootstrapServer: String): Unit = {
    runKafkaExecutable {
      ConsumerGroupCommand.main(Array(
        "--list",
        "--bootstrap-server", bootstrapServer
      ))
    }
  }
}
