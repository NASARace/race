package gov.nasa.race.common

import java.io.PrintStream

import gov.nasa.race.util.ConsoleIO.{resetColor, reverseColor}

/**
  * the generic container for statistics. This can be sent as a message, as long as
  * the snapshot element type is serializable
  */
case class Stats (
  topic: String,
  takeMillis: Long,
  elapsedMillis: Long,
  data: Any
)

/**
  * type to visualize Stats
  */
trait StatsPrinter {
  // can be called from show()
  def showTopic (stats: Stats, ps: PrintStream) = {
    println(f"$reverseColor${stats.topic}%-80s$resetColor")
  }

  /** the entry method for Stats visualization */
  def show (stats: Stats, ps: PrintStream = Console.out): Unit
}

object StatsPrinter {
  class StatsPrinterEntry (val statsDataType: Class[_], val printer: StatsPrinter)

  private var knownPrinters: List[StatsPrinterEntry] = List(
    new StatsPrinterEntry(classOf[Any],new UnknownStatsPrinter)
  )

  def printerForStatsData (statsData: Any): Option[StatsPrinter] = {
    val statsDataType = statsData.getClass
    knownPrinters foreach { e=> if (e.statsDataType.isAssignableFrom(statsDataType)) return Some(e.printer) }
    None
  }

  def registerPrinter (statsDataType: Class[_], printer: StatsPrinter): Boolean = {
    // note - last reg for same statsType wins
    val e = new StatsPrinterEntry(statsDataType,printer)
    knownPrinters = e :: knownPrinters.filterNot(e => e.statsDataType eq statsDataType)
    true
  }
}

/**
  * the catch-all StatsPrinter
  */
class UnknownStatsPrinter extends StatsPrinter {
  def show (stats: Stats, ps: PrintStream) = {
    ps.println(s"unknown statistics: ${stats.topic} (${stats.data.getClass})")
  }
}