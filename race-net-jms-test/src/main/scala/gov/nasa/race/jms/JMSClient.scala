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

/**
 * JMS test client that uses plain ActiveMQ/JMS to access topics
 */
package gov.nasa.race.jms

import javax.jms._

import com.typesafe.config.ConfigFactory
import gov.nasa.race.main.CliArgs
import org.apache.activemq.ActiveMQConnectionFactory
import gov.nasa.race.util.ConsoleIO._

object JMSClient {
  def main (args: Array[String]): Unit = {
    System.setProperty("logback.configurationFile", "logback-jmsclient.xml") // need our own to not collide with race

    val config = ConfigFactory.load("jmsclient")

    class Opts extends CliArgs("jmsclient") {
      var host: String = config.getString("host")
      var port: Int = config.getInt("port")
      var user: String = null
      var pw: String = null
      var topic: String = config.getString("topic")

      opt1("--host")("<hostName>","hostname of server") { a => host = a }
      opt1("--port")("<portNumber>","port of server") { a => port = parseInt(a) }
      opt1("--user")("<userName>","JMS user") { a => user = a }
      opt1("--pw")("<password>","JMS password") { a => pw = a }
      opt1("--topic", "-t")("<topicName>", "JMS topic") { a => topic = a }
    }

    val cliOpts = CliArgs(args) {new Opts}.getOrElse(return)

    val topicName = cliOpts.topic
    val uri = s"tcp://${cliOpts.host}:${cliOpts.port}"
    val clientID = config.getString("client-id")
    println(s"jmsclient $clientID trying to connect to $uri for topic $topicName")

    val connectionFactory = new ActiveMQConnectionFactory(cliOpts.user, cliOpts.pw, uri)
    val connection = connectionFactory.createConnection()
    connection.setClientID(clientID)
    connection.start()

    val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val topic = session.createTopic(topicName)

    val consumer = session.createConsumer(topic)
    //val consumer = session.createDurableSubscriber(fpsChannel)
    println(s"jmsclient connected to $uri listening on topic $topicName")

    consumer.setMessageListener(new MessageListener() {
      def onMessage(message: Message) = {
        message match {
          case textMsg: TextMessage =>
            try {
              println(s"got message: ${textMsg.getText}")
            } catch {
              case ex: JMSException =>
                println(s"ERROR: $ex")
            }
          case msg => println(s"got unknown message: $msg")
        }
      }
    })

    menu("enter command [9:exit]:\n") {
      case "9" =>
        connection.close
        println("shutting down")
    }
  }
}
