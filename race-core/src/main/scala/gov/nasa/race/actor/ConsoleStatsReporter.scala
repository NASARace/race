package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.common.Stats
import gov.nasa.race.core.Messages._
import gov.nasa.race.core.SubscribingRaceActor
import gov.nasa.race.util.ConsoleIO._

import scala.collection.mutable.{SortedMap => MSortedMap}

/**
  * an actor that prints Stats objects on the (ANSI) console
  */
class ConsoleStatsReporter (val config: Config) extends SubscribingRaceActor {

  val topics = MSortedMap.empty[String,Stats]

  override def handleMessage = {
    case BusEvent(_,stats:Stats,_) =>
      topics += stats.topic -> stats
      printTopics
  }

  def printTopics = {
    if (topics.nonEmpty) {
      clearScreen
      topics.valuesIterator foreach(_.printToConsole)
      println
    }
  }
}
