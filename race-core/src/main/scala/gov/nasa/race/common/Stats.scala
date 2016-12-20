package gov.nasa.race.common

import java.io.FileWriter

import gov.nasa.race.util.ConsoleIO.{resetColor, reverseColor}
import gov.nasa.race.util.DateTimeUtils.durationMillisToHMMSS
import gov.nasa.race.util.StringUtils

/**
  * the generic container for statistics. This can be sent as a message, as long as
  * the snapshot element type is serializable
  */
trait Stats {
  val topic: String
  val takeMillis: Long
  val elapsedMillis: Long

  def printToConsole: Unit
  def printToFile (fw: FileWriter): Unit = {}

  def printConsoleHeader = {
    val title = StringUtils.padRight(topic,50, ' ')
    val elapsed = StringUtils.padLeft(durationMillisToHMMSS(elapsedMillis), 20, ' ')
    println(s"$reverseColor$title          $elapsed$resetColor")
  }
}
