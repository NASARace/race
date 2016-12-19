package gov.nasa.race.actor

import java.io.PrintStream

import com.typesafe.config.Config
import gov.nasa.race.common.{Stats, StatsPrinter, SubscriberMsgStats, SubscriberMsgStatsPrinter}
import gov.nasa.race.core.{BusEvent, SubscribingRaceActor}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.ConsoleIO._

import scala.collection.mutable.{ListBuffer, SortedMap => MSortedMap}

/**
  * an actor that prints Stats objects on the (ANSI) console
  */
class ConsoleStatsReporter (val config: Config) extends SubscribingRaceActor {

  val topics = MSortedMap.empty[String,Stats]
  registerConfiguredPrinters

  override def handleMessage = {
    case BusEvent(_,stats:Stats,_) =>
      topics += stats.topic -> stats
      printTopics
  }

  def registerConfiguredPrinters = {
    config.getOptionalConfigList("printers") foreach { pconf =>
      val statsDataType = Class.forName(pconf.getString("data"))
      val printer = newInstance[StatsPrinter](pconf.getString("printer")).get
      StatsPrinter.registerPrinter(statsDataType,printer)
    }
  }

  def printTopics = {
    if (topics.nonEmpty) clearScreen
    topics.valuesIterator foreach { stats =>
      StatsPrinter.printerForStatsData(stats.data) foreach( _.show(stats, Console.out))
    }
  }
}
