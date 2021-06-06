/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.actor

import java.io.{File, FileWriter, PrintWriter}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{PrintStats, PrintStatsFormatter, Stats, StringXmlPullParser2}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, RaceTick}
import gov.nasa.race.core.{PeriodicRaceActor, PublishingRaceActor, RaceInitializeException, SubscribingRaceActor}
import gov.nasa.race.util.DateTimeUtils.durationMillisToHMMSS
import gov.nasa.race.util.{BufferedFileWriter, ConsoleIO, FileUtils, StringUtils}

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._

/**
  * a mix-in for Stats reporter actors
  *
  * Since we couldn't type match on a generic Stats type due to type erasure (short of using shapeless), we try to make
  * the best of it by storing all Stats. That means the report implementation has to do the type checking
  *
  * Note that we can get Stats messages for different topics at any time so we don't report right away and
  * wait (up to a max delay) until we haven't received Stats updates for a configured time. For that reason
  * the reporter tickInterval should be smaller than the one used by the StatsCollectors
  */
trait StatsReporterActor extends SubscribingRaceActor with PeriodicRaceActor {

  // how long do we wait to print after receiving the last Stats message, to avoid printing in the middle of a Stats series
  val reportDelayMillis = config.getFiniteDurationOrElse("report-delay",defaultTickInterval).toMillis

  // upper bound for update delay
  val maxDelayMillis = config.getFiniteDurationOrElse("max-report-delay", 15.seconds).toMillis

  val topics = MSortedMap.empty[String,Stats]

  var lastStatsMillis: Long = 0 // when did we receive the last Stats update
  var lastReportMillis: Long = Long.MaxValue // when did we last report topics
  var hasNewStats = false

  def report: Unit  // to be provided by concrete actor

  // our default ticks, which should be finer granularity than StatsCollectors
  override def defaultTickInterval = 2300.milliseconds
  override def defaultTickDelay = 2300.milliseconds

  override def onRaceTick(): Unit = {
    if (hasNewStats) {
      val t = System.currentTimeMillis
      if (lastStatsMillis > 0) {
        if (((t - lastStatsMillis) > reportDelayMillis) || ((t - lastReportMillis) > maxDelayMillis)) {
          report
          lastReportMillis = t
          hasNewStats = false
        }
      }
    }
  }

  def handleStatsReporterMessage: Receive = {
    case BusEvent(_,stats:Stats,_) =>
      topics += stats.topic -> stats
      lastStatsMillis = System.currentTimeMillis
      hasNewStats = true
  }

  override def handleMessage = handleStatsReporterMessage
}

/**
  * a StatsReporterActor that produces print output and can be configured with formatters
  *
  * If we have a configured PrintFormatter that handles the respective Stats type, it will
  * take precedence over printing that is in the Stats
  */
trait PrintStatsReporterActor extends StatsReporterActor {
  val pw: PrintWriter // to be provided by concrete class

  val formatters: Seq[PrintStatsFormatter] = config.getConfigSeq("formatters").flatMap ( conf =>
    newInstance[PrintStatsFormatter](conf.getString("class"),Array(classOf[Config]),Array(conf))
  )

  def reportProlog: Unit = {}
  def reportEpilog: Unit = {}

  // a default header, override to conserve space
  def printHeader (stats: Stats): Unit = {
    pw.println(stats.topic)
    pw.println("========================================================================================")
    pw.print("elapsed: ")
    pw.println(durationMillisToHMMSS(stats.elapsedMillis))
    pw.print("source:  ")
    pw.println(stats.source)
    pw.println
  }

  override def report = {
    reportProlog

    topics.valuesIterator foreach { s =>
      printHeader(s)

      if (!printedWithFormatter(s)) {
        s match {
          case ps: PrintStats => ps.printWith(pw)
          case other => info(s"don't know how to print ${other.getClass.getName}")
        }
      }

      pw.println
    }

    pw.flush
    reportEpilog
  }

  def printedWithFormatter(s: Stats): Boolean = formatters.exists( _.printWith(pw,s) )
}

/**
  * a StatsReporter that prints on a ANSI console
  */
class ConsoleStatsReporter (val config: Config) extends PrintStatsReporterActor {
  val prolog = if (config.getBooleanOrElse("erase", false)) ConsoleIO.EraseScreen else ConsoleIO.ClearScreen
  val pw = new PrintWriter(System.out)

  override def printHeader (stats: Stats) = {
    val title = StringUtils.padRight(s"${stats.topic} [${stats.source}]",80, ' ')
    val elapsed = StringUtils.padLeft(durationMillisToHMMSS(stats.elapsedMillis), 20, ' ')
    pw.print(ConsoleIO.reverseColor)
    pw.print(title)
    pw.print("        ")
    pw.print(elapsed)
    pw.println(ConsoleIO.resetColor)
  }

  override def reportProlog = pw.print(prolog)
}

/**
  * a StatsReporter that writes to a file
  */
class FileStatsReporter (val config: Config) extends PrintStatsReporterActor {

  def defaultPathName = s"tmp/$name" // override in concrete class
  val reportFile = new File(config.getStringOrElse("pathname", defaultPathName))

  if (!FileUtils.ensureWritable(reportFile).isDefined) {
    val msg = s"invalid report file $reportFile"
    throw new RaceInitializeException(msg)
  }

  val writer = new BufferedFileWriter(reportFile, config.getIntOrElse("buffer-size",16384), false)
  val pw = new PrintWriter(writer)

  override def reportProlog = writer.reset
  override def reportEpilog = writer.writeFile
}

/**
  * a StatsReporter that creates, publishes and optionally saves XML reports
  *
  * TODO - add docType and xmlDecl
  */
class XMLStatsReporter (val config: Config) extends StatsReporterActor with PublishingRaceActor {
  val pathName = config.getOptionalString("pathname")

  val prettify = config.getBooleanOrElse("prettify",false)

  def save (msg: String) = {
    ifSome(pathName) { pn =>
      val writer = new FileWriter(pn,false)
      writer.write(msg,0,msg.length)
      writer.close
    }
  }

  override def report = {
    val xml = s"""<statisticsReport>${topics.values.map(_.toXML).mkString(" ")}</statisticsReport>"""

    val msg = if (prettify) {  // FIXME - this is awfully inefficient
      val parser = new StringXmlPullParser2
      parser.initialize(xml)
      parser.format
    } else xml

    save(msg)
    publish(msg)
  }
}