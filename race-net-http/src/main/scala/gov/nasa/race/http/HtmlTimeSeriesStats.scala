/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
package gov.nasa.race.http

import java.awt.{Color, Font}
import java.io.ByteArrayOutputStream

import akka.http.scaladsl.model.{HttpEntity, MediaTypes}
import gov.nasa.race.Dated
import gov.nasa.race.common.{TSEntryData, TimeSeriesStats}
import org.jfree.chart.plot.{PlotOrientation, XYPlot}
import org.jfree.chart.{ChartFactory, ChartUtils}
import org.jfree.data.xy.{XYBarDataset, XYSeries, XYSeriesCollection}

import scalatags.Text.all._

/**
  * TimeSeriesStats mix-in to report as HTML
  */
trait HtmlTimeSeriesStats[O <: Dated,E <: TSEntryData[O]] extends TimeSeriesStats[O,E] with HtmlStats {

  def toHtml = {
    import data._
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}

    val res = diagramArtifacts

    val html =
      div(
        HtmlStats.htmlTopicHeader(topic,source,elapsedMillis),
        table(cls:="noBorder")(
          tr(
            th("active"),th("min"),th("max"),th("cmplt"),th(""),
            th("n"),th("Δt min"),th("Δt max"),th("Δt avg"),th(""),
            th("stale"),th("drop"),th("blck"),th("order"),th("dup"),th("amb")
          ),
          tr(
            td(nActive),td(minActive),td(maxActive),td(completed),td(""),
            buckets match {
              case Some(bc) if bc.numberOfSamples > 0 => Seq( td(bc.numberOfSamples),td(dur(bc.min)),td(dur(bc.max)),td(dur(bc.mean)))
              case _ => Seq( td("-"),td("-"),td("-"),td("-"))
            },
            td(""), td(stale),td(dropped),td(blackout),td(outOfOrder),td(duplicate),td(ambiguous)
          )
        ),
        res.html
      )

    HtmlArtifacts(html,res.resources)
  }

  def diagramArtifacts = {
    data.buckets match {
      case Some(bc) if bc.numberOfSamples > 0 =>
        def dataset = {
          val ds = new XYSeries("updates")
          bc.processBuckets { (i, count) => ds.add(Math.round(i * bc.bucketSize / 1000).toDouble, count) }
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
        ChartUtils.writeChartAsPNG(out, chart, w, h, true, 6)
        val ba = out.toByteArray

        val path = "ts-update-histogram.png"
        val html = p(img(src:=path, width:=s"${w/2}", height:=s"${h/2}"))// down sample to increase sharpness
        val imgContent = HttpEntity(MediaTypes.`image/png`, ba)

        HtmlArtifacts(html, Map(path -> imgContent))

      case _ => HtmlArtifacts(p(""), HtmlStats.noResources)
    }
  }
}

trait BasicHtmlTimeSeriesStats[O <: Dated] extends HtmlTimeSeriesStats[O,TSEntryData[O]]
