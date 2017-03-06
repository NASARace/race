package gov.nasa.race.actor

import java.io.PrintWriter

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.ConsoleStats
import gov.nasa.race.core.Messages._
import gov.nasa.race.core.SubscribingRaceActor
import gov.nasa.race.util.ConsoleIO._

import scala.collection.mutable.{SortedMap => MSortedMap}

/**
  * an actor that prints Stats objects on the (ANSI) console
  */
class ConsoleStatsReporter (val config: Config) extends SubscribingRaceActor {

  var opw: Option[PrintWriter] = None
  val topics = MSortedMap.empty[String,ConsoleStats]

  override def onStartRaceActor(originator: ActorRef): Any = {
    opw = Some(new PrintWriter(System.out))
    super.onStartRaceActor(originator)
  }

  // no need to close if this is the console, but at some point we might want
  // to support configured output streams

  override def handleMessage = {
    case BusEvent(_,stats:ConsoleStats,_) =>
      topics += stats.topic -> stats
      printTopics
  }

  def printTopics = {
    ifSome(opw) { pw =>
      if (topics.nonEmpty) {
        clearScreen
        topics.valuesIterator foreach (_.writeToConsole(pw))
        println
        pw.flush
      }
    }
  }
}
