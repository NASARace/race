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

package gov.nasa.race.actors.exports

import java.util.Properties

import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.core._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core.Messages.RaceSystemMessage
import gov.nasa.race.core.{BusEvent, SubscribingRaceActor}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

/**
 * a RaceActor that sends string messages to a Kafka broker (list)
 */
class KafkaProducerActor (val config: Config) extends SubscribingRaceActor {

  val topicName = config.getString("kafka-topic")
  val producer = new KafkaProducer[String,String](getProducerConfig)

  def getProducerConfig: Properties = {
    val props = new Properties

    //--- mandatory entries
    props.put("metadata.broker.list", config.getStringOrElse("broker-list", "127.0.0.1:9092"))
    props.put("serializer.class", config.getStringOrElse("serializer", "kafka.serializer.StringEncoder"))

    //--- optional entries
    ifSome(config.getOptionalString("partitioner")) {
      props.put("partitioner.class", _)
    }
    ifSome(config.getOptionalString("required-acks")) {
      props.put("request.required.acks", _)
    }

    props
  }

  override def handleMessage: Receive = {
    case sysMsg: RaceSystemMessage => handleSystemMessage(sysMsg)

    case BusEvent(_,msg,_) =>
      val msgText = msg.toString
      info(s"${name} sending: $msg")
      val rec = new ProducerRecord[String,String](topicName,msgText)
      producer.send(rec)
  }
}