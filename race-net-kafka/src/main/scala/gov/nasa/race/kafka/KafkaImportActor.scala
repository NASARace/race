package gov.nasa.race.kafka

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.core.{PublishingRaceActor, RaceContext}
import gov.nasa.race.util.ThreadUtils

/**
  * a RaceActor that publishes messages received from a Kafka broker
  */
class KafkaImportActor (val config: Config) extends PublishingRaceActor {

  var consumer: Option[ConfigurableKafkaConsumer] = None // defer init since Kafka topics might be from remote config

  val thread = ThreadUtils.daemon {
    ifSome(consumer) { c =>
      forever {
        c.poll.foreach{publish}
      }
    }
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Unit = {
    consumer = createConsumer(actorConf.getConfig("consumer"))
    // we won't subscribe before we start
    super.onInitializeRaceActor(rc,actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    ifSome(consumer) { c =>
      c.subscribe
      thread.start
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(consumer) { _.unsubscribe }
    super.onTerminateRaceActor(originator)
  }

  def createConsumer(conf: Config) = {
    newInstance[ConfigurableKafkaConsumer](conf.getString("class"), Array(classOf[Config]), Array(conf))
  }
}
