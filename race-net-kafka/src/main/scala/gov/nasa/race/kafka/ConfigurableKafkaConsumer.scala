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
import java.time.{Duration => JDuration}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.ClassUtils
import gov.nasa.race.util._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer => KStringDeserializer}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.jdk.DurationConverters._
import scala.reflect._

object ConsumerState extends Enumeration {
  type Status = Value
  val Created,Subscribed,Closed = Value
}

/**
  * base type of RACE configurable KafkaConsumers, which we need to encapsulate the
  * parameterized key/value types (we can't specify generic types in configs).
  *
  * this is analogous to ConfigurableTranslators / TranslatorActors
  *
  * NOTE - kafka-clients are not downward compatible with respect to the server, i.e. a new client does not
  * work with an old server, but newer servers support older clients. For that reason race-net-kafka defaults to
  * a 0.9 kafka-clients lib
  *
  * Note also that client APIs have changed signatures and running a 0.10+ client against 0.9 client libs will cause
  * runtime errors. Since we want to leave it to the race-net-kafka client to override the kafka-clients lib we have to
  * resort to reflection calls. This is very sub-optimal
  */
abstract class ConfigurableKafkaConsumer (val config: Config) {
  type KeyType
  type ValueType

  def keyDeserializer: ClassTag[_ <: Deserializer[KeyType]]
  def valueDeserializer: ClassTag[_ <: Deserializer[ValueType]]

  val topicNames = config.getStringList("kafka-topics") // keep as java List
  val groupId = config.getStringOrElse("group-id", defaultGroupId)
  val clientId = config.getStringOrElse("client-id", defaultClientId)
  val pollTimeout: JDuration = config.getFiniteDurationOrElse("poll-timeout", 1.hour).toJava

  val consumer: KafkaConsumer[KeyType,ValueType] = createConsumer
  var state = ConsumerState.Created

  def defaultGroupId: String = "race-consumer"
  def defaultClientId: String = getClass.getSimpleName + hashCode()

  def subscribe: Boolean = {
    // consumer.subscribe(topicNames)  // would be as simple as that if Kafka would be more concerned about not breaking APIs

    val consumerCls = consumer.getClass
    var mth = ClassUtils.getMethod(consumerCls,"subscribe",classOf[java.util.List[String]]) orElse  // kafka-clients 0.9.*
              ClassUtils.getMethod(consumerCls,"subscribe",classOf[java.util.Collection[String]])  // kafka-clients 0.10.*
    mth match {
      case Some(m) =>
        try {
          m.invoke(consumer, topicNames)
          state = ConsumerState.Subscribed
          true
        } catch {
          case _: Throwable => false
        }
      case None => false
    }
  }

  // we use a pre-allocated buffer object to (1) avoid per-poll allocation, and (2) to preserve the value order
  // NOTE - this means fillValueBuffer and valueBuffer access have to happen from the same thread
  val valueBuffer = new ArrayBuffer[Any](32)

  // skip over every message on that topic which hasn't been delivered at this point
  // NOTE - this makes things source incompatible between the 0.9 and the 1.0 kafka-client
  // (the old one was an ellipsis method whereas the new one takes a Collection<TopicPartition> arg)
  //def seekToEnd = foreachInJavaIterable(consumer.assignment())( consumer.seekToEnd(_))

  def fillValueBuffer(): Int = {
    // NOTE - recs can apparently change asynchronously, hence we cannot allocate a value array and then
    // iterate over recs to fill it
    try {
      val recs = consumer.poll(pollTimeout)
      valueBuffer.clear()
      if (!recs.isEmpty) {
        val it = recs.iterator
        while (it.hasNext) valueBuffer += it.next.value
      }
      valueBuffer.size
    } catch {
      case _:WakeupException => 0
    }
  }

  // this is the thread safe way to get a KafkaConsumer out of a long poll
  def wakeUp(): Unit = {
    consumer.wakeup()
  }

  def close() = {
    consumer.unsubscribe
    consumer.close
    state = ConsumerState.Closed
  }

  def isSubscribed = state == ConsumerState.Subscribed
  def isClosed = state == ConsumerState.Closed

  def createConsumer: KafkaConsumer[KeyType,ValueType] = {
    val p = new Properties
    p.put("bootstrap.servers", config.getStringOrElse("bootstrap-servers", "localhost:9092"))
    p.put("key.deserializer", keyDeserializer.runtimeClass.getName)
    p.put("value.deserializer", valueDeserializer.runtimeClass.getName)

    p.put("group.id",groupId)
    p.put("client.id",clientId)

    // Note - Kafka version dependent: new: {earliest,latest,none} old: {smallest,largest}
    ifSome(config.getOptionalString("offset-reset")){ p.put("auto.offset.reset",_)}

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