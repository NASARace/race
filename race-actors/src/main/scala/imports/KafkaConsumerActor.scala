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

import akka.actor.ActorRef
import akka.util.Timeout
import com.sclasen.akka.kafka.{AkkaConsumer, AkkaConsumerProps, CommitConfig, StreamFSM}
import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.DateTimeUtils._
import gov.nasa.race.common._
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, _}
import kafka.serializer.StringDecoder

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * a RaceActor that imports messages from Kafka
 */
class KafkaConsumerActor (val config: Config) extends PublishingRaceActor {

  final val streamFSM = """/stream\d+""".r

  val writeTo = config.getString("write-to")
  var consumer: AkkaConsumer[String,String] = _

  def getCommitConfig (conf: Config) = {
    CommitConfig(
      commitInterval = Some(asFiniteDuration(conf.getFiniteDurationOrElse("commit-interval", 10.seconds))),
      commitAfterMsgCount = Some(conf.getIntOrElse("commit-msgcount", 1000)),
      commitTimeout = Timeout.durationToTimeout(conf.getFiniteDurationOrElse("commit-timeout", 5.seconds))
    )
  }

  def getConsumerProps (conf: Config) = {
    AkkaConsumerProps.forContext(
      context = context,
      zkConnect = conf.getStringOrElse("zookeeper-connect", "127.0.0.1:2181"),
      topic = conf.getString("kafka-topic"),
      group = conf.getStringOrElse("kafka-group", "default"),
      streams = conf.getIntOrElse("kafka-streams", 1),
      keyDecoder = new StringDecoder(),
      msgDecoder = new StringDecoder(),  // <2do> only string messages for now (1)
      receiver = self,
      commitConfig = getCommitConfig(conf)
    )
  }

  override def initializeRaceActor(rc: RaceContext, actorConf: Config): Unit = {
    super.initializeRaceActor(rc,actorConf)
    consumer = new AkkaConsumer(getConsumerProps(actorConf))
    //setInitFuture( consumer.start()) // we delay this until startRaceActor
  }

  override def startRaceActor(originator: ActorRef) = {
    super.startRaceActor(originator)
    consumer.start
  }

  override def terminateRaceActor (originator: ActorRef) = {
    super.terminateRaceActor(originator)
    // sync shutdown
    val timeout = Duration(30, SECONDS)
    val commit = consumer.commit()
    Await.ready(commit, timeout)
    val stop = consumer.stop()
    Await.ready(stop, timeout)
  }


  override def handleMessage: Receive = {
    case msg: String =>  // see (1) above - we only get String messages from the consumer thread for now
      val senderRef = sender()
      //if (!streamFSM.findFirstIn(senderRef.path.name).isEmpty) {
        info(f"${name} publishes ${msg}%20.20s..")
        publish(writeTo, msg)
        senderRef ! StreamFSM.Processed
      //}
  }
}
