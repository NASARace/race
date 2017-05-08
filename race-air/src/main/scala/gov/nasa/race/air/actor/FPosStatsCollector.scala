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
package gov.nasa.race.air.actor

import java.awt.{Color, Font}
import java.io.{ByteArrayOutputStream, PrintWriter}

import akka.http.scaladsl.model.{HttpEntity, MediaTypes}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.StatsCollector
import gov.nasa.race.air.{FlightCompleted, FlightPos}
import gov.nasa.race.common.{ConfigurableUpdateTimeSeries, ConsoleStats, Stats, TimeSeriesUpdateContext, UpdateStats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.http.{HtmlArtifacts, HtmlStats}
import org.jfree.chart.plot.{PlotOrientation, XYPlot}
import org.jfree.chart.{ChartFactory, ChartUtilities}
import org.jfree.data.xy.{XYBarDataset, XYSeries, XYSeriesCollection}
import org.joda.time.DateTime

import scalatags.Text.all._


/**
  * actor that collects update statistics for FPos objects
  */
class FPosStatsCollector (val config: Config) extends StatsCollector with TimeSeriesUpdateContext[FlightPos] {

  val fposUpdates = new ConfigurableUpdateTimeSeries[FlightPos](config,this)

  var firstPos = true
  val resetClockDiff = config.getOptionalFiniteDuration("reset-clock-diff") // sim time

  override def handleMessage = {
    case BusEvent(_, fpos: FlightPos, _) =>
      checkClockReset(fpos.date)
      fposUpdates.updateActive(fpos.cs,fpos)

    case BusEvent(_, fcomplete: FlightCompleted, _) =>
      checkClockReset(fcomplete.date)
      fposUpdates.removeActive(fcomplete.cs)

    // we do our own flight dropped handling since we also check upon first report

    case RaceTick =>
      fposUpdates.checkDropped
      publish(snapshot)
  }

  def checkClockReset (d: DateTime) = {
    if (firstPos) {
      ifSome(resetClockDiff) { dur =>
        if (elapsedSimTimeSince(d) > dur){
          resetSimClockRequest(d)
        }
      }
      firstPos = false
    }
  }

  def snapshot: Stats = {
    new FPosStats(title, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, channels, fposUpdates.snapshot)
  }

  //--- TimeSeriesUpdateContext (timestamps are checked by the caller)
  override def isDuplicate(fpos: FlightPos, last: FlightPos) = {
    fpos.position =:= last.position && fpos.altitude =:= last.altitude
  }

}

class FPosStats(val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                val updateStats: UpdateStats) extends Stats with ConsoleStats with HtmlStats {
  import updateStats._

  def time (millis: Double): String = {
    if (millis.isInfinity || millis.isNaN) {
      "     "
    } else {
      if (millis < 120000) f"${millis / 1000}%4.0fs"
      else if (millis < 360000) f"${millis / 60000}%4.1fm"
      else f"${millis / 360000}%4.1fh"
    }
  }

  def writeToConsole(pw:PrintWriter) = {
    pw.println(consoleHeader)
    pw.println(s"observed channels: $channels")

    pw.println("active    min    max   cmplt stale  drop order   dup ambig        n dtMin dtMax dtAvg")
    pw.println("------ ------ ------   ----- ----- ----- ----- ----- -----  ------- ----- ----- -----")
    pw.print(f"$nActive%6d $minActive%6d $maxActive%6d   $completed%5d $stale%5d $dropped%5d $outOfOrder%5d $duplicate%5d $ambiguous%5d ")

    buckets match {
      case Some(bc) if bc.nSamples > 0 =>
        pw.println(f"  ${bc.nSamples}%6d ${time(bc.min)} ${time(bc.max)} ${time(bc.mean)}")
        bc.processBuckets((i, c) => {
          if (i % 6 == 0) pw.println // 6 buckets per line
          pw.print(f"${Math.round(i * bc.bucketSize / 1000)}%3ds: $c%6d | ")
        })
      case _ => // no buckets to report
    }
    pw.println
  }

  def toHtml = {
    val res = diagramArtifacts

    val html =
      div(
        HtmlStats.htmlTopicHeader(topic,channels,elapsedMillis),
        table(cls:="noBorder")(
          tr(
            th("active"),th("min"),th("max"),th("cmplt"),th(""),
            th("n"),th("Δt min"),th("Δt max"),th("Δt avg"),th(""),
            th("stale"),th("drop"),th("order"),th("dup"),th("amb")
          ),
          tr(
            td(nActive),td(minActive),td(maxActive),td(completed),td(""),
            buckets match {
              case Some(bc) if bc.nSamples > 0 => Seq( td(bc.nSamples),td(time(bc.min)),td(time(bc.max)),td(time(bc.mean)))
              case _ => Seq( td("-"),td("-"),td("-"),td("-"))
            },
            td(""), td(stale),td(dropped),td(outOfOrder),td(duplicate),td(ambiguous)
          )
        ),
        res.html
      )

    HtmlArtifacts(html,res.resources)
  }

  def diagramArtifacts = {
    buckets match {
      case Some(bc) if bc.nSamples > 0 =>
          def dataset = {
            val ds = new XYSeries("updates")
            bc.processBuckets { (i, count) => ds.add(Math.round(i * bc.bucketSize / 1000), count) }
            val sc = new XYSeriesCollection
            sc.addSeries(ds)
            new XYBarDataset(sc, 10.0)
          }

          val chart = ChartFactory.createXYBarChart(null, "sec", false, "updates", dataset,
            PlotOrientation.HORIZONTAL, false, false, false)
          val plot = chart.getPlot.asInstanceOf[XYPlot]
          plot.setBackgroundAlpha(0)
          plot.setDomainGridlinePaint(Color.lightGray)
          plot.setRangeGridlinePaint(Color.lightGray)

          val labelFont = new Font("Serif", Font.PLAIN, 30)
          val domainAxis = plot.getDomainAxis
          domainAxis.setInverted(true)
          domainAxis.setLabelFont(labelFont)
          val rangeAxis = plot.getRangeAxis
          rangeAxis.setLabelFont(labelFont)

          val w = 800
          val h = 300
          val out = new ByteArrayOutputStream
          ChartUtilities.writeChartAsPNG(out, chart, w, h, true, 6)
          val ba = out.toByteArray

          val path = "fpos-update-histogram.png"
          val html = p(img(src:=path, width:=s"${w/2}", height:=s"${h/2}"))// down sample to increase sharpness
          val imgContent = HttpEntity(MediaTypes.`image/png`, ba)

          HtmlArtifacts(html, Map(path -> imgContent))

      case _ => HtmlArtifacts(p(""), HtmlStats.noResources)
    }
  }
}
