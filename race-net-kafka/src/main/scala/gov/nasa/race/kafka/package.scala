package gov.nasa.race

/**
  * package to support export and import to/from a external Kafka broker via actors.
  *
  * This follows the same design as TranslatorActor/Translator, i.e. consists of a
  * generic actor that is configured with a KafkaProducer/Consumer which specifies
  * the respective key/value types and encapsulates the relevant Kafka settings, such
  * as
  *
  * {{{
  *   { name = "kafkaImporter"
  *     class = "gov.nasa.race.kafka.KafkaImportActor"
  *     write-to = "kafka-in"
  *     consumer {
  *       class = "gov.nasa.race.kafka.NonKeyedStringConsumer"
  *       kafka-topic = "test"
  *     }
  *   }
  * }}}
  *
  * Note this does not include Kafka or Zookeeper servers. Respective classes can be found in
  * the race-net-kafka-test project
  */
package object kafka {

}
