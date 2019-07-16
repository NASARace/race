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
 * JMS server simulator that uses plain JMS/ActiveMQ
 */

package gov.nasa.race.jms

import javax.jms.{MessageProducer, Session}

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.ConsoleIO._

object JMSServer {

  def getConfig(args: Array[String]): Config = {
    if (args.length > 0){
      ConfigFactory.parseFile( new java.io.File(args(0))).resolve
    } else {
      ConfigFactory.load("jmsserver").resolve
    }
  }

  def produce (session: Session, producer: MessageProducer, msgText: String): Unit = {
    var n = 0

    menu("enter command [1:send message, 9:exit]:\n") {
      case "1" =>
        n += 1
        val text = msgText + DateTime.now
        val message = session.createTextMessage()
        message.setText(text)
        println(s"producer publishing message $text")
        producer.send(message)
        repeatMenu

      case "9" => println("shutting down..")
    }
  }

  def main (args: Array[String]) = {
    System.setProperty("logback.configurationFile", "logback-jmsserver.xml") // need our own to not collide with race
    val config = getConfig(args)

    val topicName = config.getString("topic")
    val internalBrokerURI = config.getString("internal-broker-uri") // "vm://localhost"
    val externalBrokerURI = config.getString("external-broker-uri") // "tcp://localhost:61616"
    val msgText = config.getString("message")

    val broker = new BrokerService()

    try {
      //--- set up & start broker
      broker.setPersistent(false)
      broker.setUseJmx(false)
      broker.setDataDirectory("data/")
      broker.addConnector(internalBrokerURI)
      broker.addConnector(externalBrokerURI)
      broker.start()

      //--- create&start connection, session, topic and producer
      val connectionFactory = new ActiveMQConnectionFactory(internalBrokerURI)
      val connection = connectionFactory.createConnection()
      connection.start()

      val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
      val topic = session.createTopic(topicName)
      val producer = session.createProducer(topic)

      produce(session, producer, msgText)

      connection.close

    } catch {
      case ex: Throwable => println(s"caught exception $ex")

    } finally {
      try {
        broker.stop()
      } catch {
        case ex: Throwable => println("jmsserver failed to shut down")
      }
    }
  }
}
