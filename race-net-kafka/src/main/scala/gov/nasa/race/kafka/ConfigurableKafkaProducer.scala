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

import java.util.Properties
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.ifSome
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.{Serializer, StringSerializer => KStringSerializer}


/**
  * base type of RACE configurable KafkaProducers, which we need to encapsulate the
  * parameterized key/value types (we can't specify generic types in configs).
  *
  * this is analogous to ConfigurableTranslators / TranslatorActors
  */
abstract class ConfigurableKafkaProducer (val config: Config) {
  type KeyType
  type ValueType

  def keySerializerClass: Class[_ <: Serializer[KeyType]]
  def valueSerializerClass: Class[_ <: Serializer[ValueType]]

  val groupId = config.getStringOrElse("group-id", defaultGroupId)
  val clientId = config.getStringOrElse("client-id", defaultClientId)
  val topicName = config.getStringOrElse("kafka-topic", "")  // there can only be one

  val producer: KafkaProducer[KeyType,ValueType] = createProducer

  def defaultGroupId: String = "race-producer"
  def defaultClientId: String = getClass.getSimpleName + hashCode()

  def close (): Unit = {
    producer.close()
  }

  def send(msg: Any): Unit = {
    producer.send(producerRecord(msg))
  }

  def producerRecord (msg: Any): ProducerRecord[KeyType,ValueType]

  def createProducer: KafkaProducer[KeyType,ValueType] = {
    val p = new Properties
    p.put("bootstrap.servers", config.getStringOrElse("bootstrap-servers", "localhost:9092"))
    p.put("key.serializer", keySerializerClass.getName)
    p.put("value.serializer", valueSerializerClass.getName)
    p.put("group.id", groupId)
    p.put("client.id", clientId)

    new KafkaProducer(p)
  }
}

trait StringKeyedKafkaProducer {
  type KeyType = String
  def keySerializerClass = classOf[KStringSerializer]
}

/**
  * a ConfigurableKafkaProducer that sends non-keyed String value
  */
class NonKeyedStringProducer (conf: Config) extends ConfigurableKafkaProducer(conf) with StringKeyedKafkaProducer {
  type ValueType = String
  def valueSerializerClass = classOf[KStringSerializer]

  override def producerRecord (msg: Any) = {
    new ProducerRecord(topicName,msg.toString)
  }
}