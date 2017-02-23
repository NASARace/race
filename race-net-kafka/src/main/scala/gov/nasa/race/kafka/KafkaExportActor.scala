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

import com.typesafe.config.Config
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.SubscribingRaceActor

/**
  * a RaceActor that sends received messages to a Kafka broker
  *
  * for now, this is just a configurable actor interface for a plain KafkaProducer. We don't handle
  * things like back pressure yet
  */
class KafkaExportActor(val config: Config) extends SubscribingRaceActor {

  val producer: ConfigurableKafkaProducer = createProducer(config.getConfig("producer"))

  def createProducer(conf: Config) = {
    val prod = newInstance[ConfigurableKafkaProducer](conf.getString("class"), Array(classOf[Config]), Array(conf)).get
    info(s"instantiated producer ${prod.getClass.getName}")
    prod
  }

  override def handleMessage: Receive = {
    case BusEvent(_,msg,_) =>
      info(s"${name} sending: $msg")
      producer.send(msg)
  }
}