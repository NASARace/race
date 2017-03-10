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
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer => KStringDeserializer}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.reflect._

/**
  * base type of RACE configurable KafkaConsumers, which we need to encapsulate the
  * parameterized key/value types (we can't specify generic types in configs).
  *
  * this is analogous to ConfigurableTranslators / TranslatorActors
  */
abstract class ConfigurableKafkaConsumer (val config: Config) {
  type KeyType
  type ValueType

  def keyDeserializer: ClassTag[_ <: Deserializer[KeyType]]
  def valueDeserializer: ClassTag[_ <: Deserializer[ValueType]]

  val topicNames = config.getStringListOrElse("kafka-topics", Seq.empty[String]) // there can be many
  val pollTimeoutMs = config.getFiniteDurationOrElse("poll-timeout", 1.hour).toMillis

  val consumer: KafkaConsumer[KeyType,ValueType] = createConsumer

  def subscribe = consumer.subscribe(topicNames.asJava)

  // we use a pre-allocated buffer object to (1) avoid per-poll allocation, and (2) to preserve the value order
  // NOTE - this means fillValueBuffer and valueBuffer access have to happen from the same thread
  val valueBuffer = new ArrayBuffer[Any](32)

  def fillValueBuffer: Int = {
    // NOTE - recs can apparently change asynchronously, hence we cannot allocate a value array and then
    // iterate over recs to fill it
    val recs = consumer.poll(pollTimeoutMs)
    valueBuffer.clear
    if (!recs.isEmpty) {
      val it = recs.iterator
      while (it.hasNext) valueBuffer += it.next.value
    }
    valueBuffer.size
  }

  def unsubscribe = consumer.unsubscribe

  def createConsumer: KafkaConsumer[KeyType,ValueType] = {
    val p = new Properties
    p.put("bootstrap.servers", config.getStringOrElse("bootstrap-servers", "127.0.0.1:9092"))
    p.put("key.deserializer", keyDeserializer.runtimeClass.getName)
    p.put("value.deserializer", valueDeserializer.runtimeClass.getName)
    p.put("group.id", config.getStringOrElse("kafka-group", "race"))

    // TODO - we should also be able to configure other commit policies (e.g. each message)
    p.put("enable.auto.commit", "true")
    p.put("auto.commit.interval.ms", config.getFiniteDurationOrElse("kafka-autocommit-interval", 1.second).toMillis.toString)
    p.put("request.timeout.ms", config.getFiniteDurationOrElse("kafka-request-timeout", 60.seconds).toMillis.toString)
    p.put("session.timeout.ms", config.getFiniteDurationOrElse("kafka-session-timeout", 30.seconds).toMillis.toString)

    new KafkaConsumer(p)
  }
}

trait StringKeyedKafkaConsumer {
  type KeyType = String
  def keyDeserializer = classTag[KStringDeserializer]
}

class NonKeyedStringConsumer (conf: Config) extends ConfigurableKafkaConsumer(conf) with StringKeyedKafkaConsumer {
  type ValueType = String
  def valueDeserializer = classTag[KStringDeserializer]
}