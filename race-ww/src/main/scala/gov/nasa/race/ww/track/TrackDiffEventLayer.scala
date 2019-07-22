/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.ww.track

import java.awt.Color
import java.awt.image.BufferedImage

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.Query
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.track.TrackPairEvent
import gov.nasa.race.trajectory.TrajectoryDiff
import gov.nasa.race.util.StringUtils
import gov.nasa.race.ww.{Images, RaceViewer}

class TrackDiffEventQuery[T](getEvent: T=>T) extends Query[T] {
  override def error(msg: String): Unit = {
    // TODO
  }

  override def getMatchingItems(query: String, items: Iterable[T]): Iterable[T] = {
    items // TODO
  }
}

class TrackDiffEventFields extends TrackPairEventFields {
  val id = addField("id:")
  val time = addField("time:")
  val refSrc = addField("refSrc:")
  val diffSrc = addField("diffSrc:")
  val nPoints = addField("nPoints:")
  addSeparator
  val minDist = addField("minDist:")
  val maxDist = addField("maxDist:")
  val avgDist = addField("avgDist")
  val varDist = addField("varDist:")
  addSeparator
  val avgAngle = addField("avgAngle:")
  val varAngle = addField("varAngle:")

  setContents

  def update (e: TrackPairEvent): Unit = {
    e.extraData match {
      case Some(diff:TrajectoryDiff) =>
        id.text = e.id
        time.text = e.date.format_Hms
        refSrc.text = diff.refSource
        diffSrc.text = diff.diffSource
        nPoints.text = diff.numberOfSamples.toString
        minDist.text = diff.distance2DStats.min.showRounded
        maxDist.text = diff.distance2DStats.max.showRounded
        avgDist.text = diff.distance2DStats.mean.showRounded
        varDist.text = f"${diff.distance2DStats.variance}%.3f"
        avgAngle.text = diff.angleDiffStats.mean.showRounded
        varAngle.text = f"${diff.angleDiffStats.variance}%.3f"
      case other => // ignore?
    }
  }
}

class TrackDiffEventPanel(override val layer: TrackDiffEventLayer)  extends TrackPairEventPanel(layer) {
  override def createFieldPanel = new TrackDiffEventFields
}

class TrackDiffEventLayer (override val raceViewer: RaceViewer, override val config: Config)
                                               extends TrackPairEventLayer(raceViewer, config) {
  override protected def createEntryPanel: TrackDiffEventPanel = new TrackDiffEventPanel(this)

  override def symbolImage (e: TrackPairEvent): BufferedImage = Images.getArrowImage(color)
  override def symbolHeading (e: TrackPairEvent): Double =  ((e.hdg1 + e.hdg2) / 2).toDegrees

  override def pos1Image (e: TrackPairEvent) = None
  override def pos1Label (e: TrackPairEvent): Option[String] = e.flatMapExtra { td: TrajectoryDiff =>
    None
    //if (isSignificantDeviation(td)) Some(StringUtils.lastPathElement(td.refSource)) else None
  }

  override def pos2Image (e: TrackPairEvent) = None
  override def pos2Label (e: TrackPairEvent): Option[String] = e.flatMapExtra { td: TrajectoryDiff =>
    None
    //if (isSignificantDeviation(td)) Some(StringUtils.lastPathElement(td.diffSource)) else None
  }

  override def layerObjectDataHeader: String =  "avg[m]  max[m]       SD"

  override def layerObjectDataText (e: TrackPairEventEntry): String = {
    e.event.withExtraOrElse("?"){ td: TrajectoryDiff =>
      f"${td.distance2DStats.mean.toMeters}%6.0f  ${td.distance2DStats.max.toMeters}%6.0f  ${td.distance2DStats.sigma}%7.2f"
    }
  }

  def isSignificantDeviation(td: TrajectoryDiff): Boolean = td.distance2DStats.max.toMeters > 30
}
