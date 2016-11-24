package gov.nasa.race.kafka

import java.util.Properties

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
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

  val topicName = config.getStringOrElse("kafka-topic", "")  // there can only be one
  val producer: KafkaProducer[KeyType,ValueType] = createProducer

  def send(msg: Any): Unit = producer.send(producerRecord(msg))

  def producerRecord (msg: Any): ProducerRecord[KeyType,ValueType]

  def createProducer: KafkaProducer[KeyType,ValueType] = {
    val p = new Properties
    p.put("bootstrap.servers", config.getStringOrElse("bootstrap-servers", "127.0.0.1:9092"))
    p.put("key.serializer", keySerializerClass.getName)
    p.put("value.serializer", valueSerializerClass.getName)

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