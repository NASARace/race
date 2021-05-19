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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.core.RaceContext
import gov.nasa.race.core.RaceActorCapabilities._
import gov.nasa.race.util.ThreadUtils
import gov.nasa.race.config.ConfigUtils._

/**
  * a RaceActor that publishes messages received from a Kafka broker
  *
  * TODO - shutdown does not work yet if server is terminated (somewhat artificial case that happens in reg tests)
  */
class KafkaImportActor (val config: Config) extends FilteringPublisher {

  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset

  val flushOnStart = config.getBooleanOrElse("flush-on-start", true)

  var consumer: Option[ConfigurableKafkaConsumer] = None // defer init since Kafka topics might be from remote config
  @volatile var terminate = false
  @volatile var listening = false

  val thread = ThreadUtils.daemon {
    ifSome(consumer) { c =>
      if (flushOnStart) {
        // drop anything that might have been queued up to this point. The intention is to
        // avoid back pressure for high volume topics. We already registered during initialization
        // so there might be accumulated messages. At the same time, initial processing of them
        // will take more time/resources since classes are not loaded and methods are not JITed.
        // The start time is non-deterministic anyways, but we still do want to keep the
        // consistency guarantee, i.e. once we start to process messages we don't drop any of them

        // NOTE - ths should not be necessary if "auto.offset.reset" is set to "latest" (but beware of the version incompatibility there)
        //c.seekToEnd
      }

      while (!terminate) {
        try {
          listening = true
          if (c.fillValueBuffer() > 0) {
            c.valueBuffer.foreach(publishFiltered)
          }
        } catch {
          case x:Throwable if !terminate => error(s"exception during Kafka read: ${x.getMessage}")
        }
      }
      c.close()
    }
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    consumer = createConsumer(actorConf.getConfig("consumer"))
    // we won't subscribe before we start
    super.onInitializeRaceActor(rc,actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    ifSome(consumer) { c =>
      if (c.subscribe) {
        info(s"subscribed to Kafka topics: ${c.topicNames}")
        thread.start()

        // apparently there is a race condition in the innards of KafkaConsumer if auto.offset.reset=latest and
        // we have a new group/concurrent producers
        //Thread.sleep(2000)

      } else {
        warning("Kafka topic subscription failed")
      }
    }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    terminate = true
    while (thread.isAlive) {
      ifSome(consumer){ _.wakeUp() }
      Thread.sleep(100)
    }

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = handleFilteringPublisherMessage

  def createConsumer(conf: Config) = {
    newInstance[ConfigurableKafkaConsumer](conf.getString("class"), Array(classOf[Config]), Array(conf))
  }
}
