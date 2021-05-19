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
