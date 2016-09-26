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

package gov.nasa.race.actors.imports

import javax.jms.{Connection, JMSException, Message, MessageConsumer, MessageListener, Session, TextMessage, Topic => JMSTopic}

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.actors.FilteringPublisher
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, _}
import org.apache.activemq.ActiveMQConnectionFactory
import org.joda.time.DateTime

import scala.language.postfixOps

object JMSImportActor {
  // connection objects are shared, this is where the runtime cost is
  // NOTE - RACE is supposed to run as ONE logical user, i.e. we only need valid credentials
  // for the first connection. We do assume all components distributed with RACE share the same
  // protection domain
  case class ConnectionEntry(val connection: Connection, val clients: Set[JMSImportActor]) {
    def + (client: JMSImportActor) = copy(clients = clients + client)
    def - (client: JMSImportActor) = copy(clients = clients - client)
  }

  private var connections: Map[String,ConnectionEntry] = Map.empty
  private var factories: Map[String,ActiveMQConnectionFactory] = Map.empty

  def requestConnection (importer: JMSImportActor): Option[Connection] = synchronized {
    val brokerURI = importer.brokerURI
    val config = importer.config

    ifSome(connections.get(brokerURI)){ e =>
      connections = connections + (brokerURI -> (e + importer))
      return Some(e.connection)
    }

    importer.info(s"attempting to connect to $brokerURI")
    val factory = factories.get(brokerURI) match {
      case Some(f) => f
      case None =>
        val factory = new ActiveMQConnectionFactory(brokerURI)
        factories = factories + (brokerURI -> factory)
        factory
    }

    implicit val client = importer.getClass
    ifNotNull(factory.createConnection(config.getVaultableStringOrElse("user", null),
                                       config.getVaultableStringOrElse("pw", null))) { c =>
      c.setClientID(importer.jmsId)
      c.start()
      connections = connections + (brokerURI -> ConnectionEntry(c, Set(importer)))
      return Some(c)
    }

    None
  }

  // the last session closes the connection
  def releaseConnection(importer: JMSImportActor, connection: Connection) = synchronized {
    val brokerURI = importer.brokerURI

    ifSome(connections.get(brokerURI)) { e =>
      val clients = e.clients

      if (clients.contains(importer)) {
        if (clients.size == 1) {
          connection.stop()
          connection.close()
          importer.info(s"closed connection to $brokerURI")
          connections = connections - brokerURI
        } else {
          connections = connections + (brokerURI -> (e - importer))
        }
      }
    }
  }
}
import gov.nasa.race.actors.imports.JMSImportActor._

/**
  * actor that imports messages from a JMS server and publishes them to the bus
  *
  * Server URI, JMS topic and bus channel are configured. We assume a JMSImportActor
  * instance per import topic so that we don't have to add additional mapping of
  * topics to bus channels.
  *
  * <todo> - reconnection and timeouts
  */
class JMSImportActor(val config: Config) extends FilteringPublisher {

  // NOTE - the listener executes in non-Akka threads (multiple!)
  private[this] class Listener(val topic: JMSTopic) extends MessageListener {
    override def onMessage(message: Message): Unit = {
      message match {
        case textMsg: TextMessage =>
          try {
            publishFiltered(textMsg.getText)
          } catch {
            case ex: JMSException => error(s"listener exception: $ex")
          }
        case msg => warning(s"listener got unknown ${topic.getTopicName} message: ${msg.getClass}")
      }
    }
  }

  val brokerURI = config.getVaultableStringOrElse("broker-uri", "tcp://localhost:61616")
  val jmsId = config.getStringOrElse("jms-id", self.path.name + System.currentTimeMillis.toHexString)
  val jmsTopic = config.getString("jms-topic")

  //--- our state data
  var connection: Option[Connection] = None
  var session: Option[Session] = None
  var consumer: Option[MessageConsumer] = None


  //--- end initialization

  // note that we return the next state here
  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(raceContext,actorConf)

    try {
      connection = requestConnection(this)
      info(s"connected to $brokerURI")

    } catch {
      case ex: Throwable =>
        warning(s"failed to open connection: $ex")
    }
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)

    session = createSession(connection)
    if (session.isDefined){
      consumer = createConsumer(session)
      if (consumer.isDefined) info(s"opened session on jms topic '$jmsTopic'")
      else warning(s"failed to create JMS consumer for jms topic '$jmsTopic'")
    } else warning(s"failed to create JMS session for connection '$brokerURI'")
  }

  def createSession (connection: Option[Connection]) = {
    tryWithSome(connection)( _.createSession(false, Session.AUTO_ACKNOWLEDGE))
  }
  def createConsumer (session: Option[Session]) = {
    tryWithSome(session)( s => {
      val topic = s.createTopic(jmsTopic)
      if (topic != null) {
        val c = s.createConsumer(topic)
        if (c != null) {
          c.setMessageListener(new Listener(topic))
          c
        } else null // no consumer
      } else null // no topic
    })
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)

    info(s"closing session")
    try {
      session.foreach(_.close())
      connection.foreach( releaseConnection(this,_))
    } catch {
      case ex: Throwable => warning(s"failed to close session: $ex")
    }
  }
}
