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

package gov.nasa.race.jms

import javax.jms.{Connection, MessageProducer, Session}

import akka.actor.ActorRef
import com.typesafe.config.{Config, ConfigFactory}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core._
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService

/**
  * actor that represents an embedded (ActiveMQ) JMS broker
  *
  * The broker provides an internal and external connector with configured URIs.
  * This is to ensure that internal connections can make use of in-process
  * mechanisms that do not require network APIs such as tcp sockets.
  */

object JMSExportActor {

  class RaceBrokerService (amqConfig: Config) extends BrokerService {
    val internalURI = amqConfig.getStringOrElse("export.internal-uri", "vm://localhost")

    setPersistent(false)
    setUseJmx(amqConfig.getBooleanOrElse("use-jmx", false))
    setDataDirectory(amqConfig.getStringOrElse("export.data-dir", "data"))
    addConnector(amqConfig.getStringOrElse("export.external-uri", "tcp://localhost:61616"))
    addConnector(internalURI)
  }

  var brokerService: Option[RaceBrokerService] = None
  var clients = Set.empty[JMSExportActor]

  def getBrokerURIfor(client: JMSExportActor): String = {
    if (brokerService.isEmpty) {
      val config = client.getUniverseConfigOrElse("activemq", ConfigFactory.load("activemq.conf"))
      brokerService = Some(new RaceBrokerService(config))
      brokerService.foreach(_.start())
    }
    clients = clients + client
    brokerService.get.internalURI
  }

  def removeClient (client: JMSExportActor) = {
    clients = clients - client
    if (clients.isEmpty){
      brokerService.foreach (_.stop)
      brokerService = None
    }
  }

  case class LiveConnection (connection: Connection, session: Session, producer: MessageProducer)
}
import gov.nasa.race.jms.JMSExportActor._


class JMSExportActor(val config: Config) extends SubscribingRaceActor {

  val topicName = config.getString("jms-topic")
  var conn: Option[LiveConnection] = None

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    info(s"initializing $name")

    // this is from a try context that would catch and report exceptions
    val brokerURI = getBrokerURIfor(this)
    val connectionFactory = new ActiveMQConnectionFactory(brokerURI)
    val connection = connectionFactory.createConnection()
    val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val jmsTopic = session.createTopic(topicName)
    val producer = session.createProducer(jmsTopic)

    connection.start()
    conn = Some(LiveConnection(connection,session,producer))

    super.onInitializeRaceActor(raceContext, actorConf)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    info(s"terminating $name")

    conn = conn match {
      case Some(LiveConnection(connection,session,producer)) =>
        info(s"closing connection for $name")

        if (!isTerminating) { // otherwise the broker shutdown hooks will do it for us and we might trip them
          session.close()
          connection.stop()
          //connection.close()
        }
        removeClient(this)
        None
      case None => None // ignore
    }

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case BusEvent(_,msg:String,_) =>
      conn match {
        case Some(LiveConnection(_,session,producer)) =>
          info(s"$name sending JMS message: $msg")
          val message = session.createTextMessage()
          message.setText(msg)
          producer.send(message)
        case None => // ignore
      }
  }
}
