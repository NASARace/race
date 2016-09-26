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
 * JMS (SWIM) client that uses plain ActiveMQ/JMS to access topics
 */
package gov.nasa.race.swimclient

import com.typesafe.config.{ Config, ConfigFactory }
import javax.jms.{ Connection, Message, TextMessage, Session, MessageListener, JMSException }
import org.apache.activemq.ActiveMQConnectionFactory
import gov.nasa.race.common.ConsoleIO._
import scopt.OptionParser

object MainSimple extends App {
  System.setProperty("logback.configurationFile", "logback-swimclient.xml") // need our own to not collide with race

  val config = ConfigFactory.load("swimclient")

  case class CliOpts (host: String=config.getString("host"),
                      port: Int=config.getInt("port"),
                      user: String=null,
                      pw: String=null,
                      topic: String=config.getString("topic"))
  def cliParser = {
    new OptionParser[CliOpts]("swimclient") {
      help("help") abbr ("h") text ("print this help")
      opt[String]('h', "host") text ("hostname of server") optional() action {(h, o) => o.copy(host = h)}
      opt[Int]('p', "port") text ("server port to connect to") optional() action {(p, o) => o.copy(port = p)}
      opt[String]("user") text ("user") optional() action {(s, o) => o.copy(user = s)}
      opt[String]("pw") text ("password") optional() action {(s, o) => o.copy(pw = s)}
      opt[String]('t', "topic") text ("JMS topic") action {(t, o) => o.copy(topic = t)}
    }
  }
  val cliOpts: CliOpts = cliParser.parse(args, CliOpts()).get

  val topicName = cliOpts.topic
  val uri = s"tcp://${cliOpts.host}:${cliOpts.port}"

  val clientID = config.getString("client-id")

  val connectionFactory = new ActiveMQConnectionFactory(cliOpts.user, cliOpts.pw, uri)
  val connection = connectionFactory.createConnection()
  connection.setClientID(clientID)
  connection.start()

  val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  val topic = session.createTopic(topicName)

  val consumer = session.createConsumer(topic)
  //val consumer = session.createDurableSubscriber(fpsChannel)
  println(s"swimclient connected to $uri listening on topic $topicName")

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
