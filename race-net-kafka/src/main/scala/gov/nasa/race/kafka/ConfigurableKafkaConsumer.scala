package gov.nasa.race.kafka

import java.util.Properties

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer => KStringDeserializer}

import scala.collection.JavaConverters._
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

  def poll: Seq[Any] = {
    val recs = consumer.poll(pollTimeoutMs)
    if (recs.isEmpty) Seq.empty
    else {
      // not very scalatic, but we want to avoid temporary objects since this could be called frequently
      val values = Array[Any](recs.count)
      forIterator(recs.iterator.asScala) { (e,i) => values(i) = e.value }  // the iterator conversion sucks
      values
    }
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