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

import com.typesafe.config.Config
import gov.nasa.race.actor.TrackDiffEvent
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.common.Query
import gov.nasa.race.swing.FieldPanel
import gov.nasa.race.trajectory.{Trajectory, TrajectoryDiff}
import gov.nasa.race.util.DateTimeUtils.hhmmss
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww.{AltitudeSensitiveRaceLayer, ConfigurableRenderingLayer, InteractiveLayerInfoPanel, InteractiveLayerObjectPanel, InteractiveRaceLayer, LayerObject, LayerSymbolOwner, RaceViewer, SubscribingRaceLayer}
import gov.nasa.worldwind.render.{Material, Path}

import scala.collection.mutable.{Map => MutableMap}

// TODO - too much overlap with TrackPairEventLayer. Unify

class TrackDiffrEventQuery[T](getEvent: T=>TrackDiffEvent) extends Query[T] {
  override def error(msg: String): Unit = {
    // TODO
  }

  override def getMatchingItems(query: String, items: Iterable[T]): Iterable[T] = {
    items // TODO
  }
}

class TrackDiffEventFields extends FieldPanel {
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

  def update (e: TrackDiffEvent): Unit = {
    val diff: TrajectoryDiff = e.diff

    id.text = e.id
    time.text = hhmmss.print(e.date)
    refSrc.text = e.refSource
    diffSrc.text = e.diffSource
    nPoints.text = diff.numberOfSamples.toString
    minDist.text = diff.distance2DStats.min.showRounded
    maxDist.text = diff.distance2DStats.max.showRounded
    avgDist.text = diff.distance2DStats.mean.showRounded
    varDist.text = f"${diff.distance2DStats.variance}%.3f"
    avgAngle.text = diff.angleDiffStats.mean.showRounded
    varAngle.text = f"${diff.angleDiffStats.variance}%.3f"
  }
}

class TrackDiffEventPanel(override val layer: TrackDiffEventLayer)
           extends InteractiveLayerObjectPanel[TrackDiffEventEntry,TrackDiffEventFields](layer) {

  override def createFieldPanel = new TrackDiffEventFields

  def setEntry (e: TrackDiffEventEntry): Unit = {
    entry = Some(e)
    fields.update(e.event)
  }
}

// TODO unify with TrackPairEventAntry
abstract class TrackDiffEventEntry(val event: TrackDiffEvent, val layer: TrackDiffEventLayer)
                                                  extends LayerObject with LayerSymbolOwner {

  protected var refPath:  Path = createPath(event.diff.refTrajectory, layer.lineMaterial)
  protected var diffPath:  Path = createPath(event.diff.diffTrajectory, layer.diffMaterial)


  def createPath (traj: Trajectory, mat: Material): TrajectoryPath = new TrajectoryPath(traj,mat)
}

// TODO unify with TrackPairEventLayer
abstract class TrackDiffEventLayer (val raceViewer: RaceViewer, val config: Config)
  extends SubscribingRaceLayer
    with ConfigurableRenderingLayer
    with AltitudeSensitiveRaceLayer
    with InteractiveRaceLayer[TrackDiffEventEntry] {

  val panel = createLayerInfoPanel
  val entryPanel = createEntryPanel

  val events = MutableMap[String,TrackDiffEventEntry]()

  val diffMaterial = new Material(config.getColorOrElse("diff-color", Color.orange))

  //--- creators
  protected def createEventEntry (event: TrackDiffEvent): TrackDiffEventEntry = {
    //new TrackDiffEventEntry(event, this)
    null
  }

  protected def createLayerInfoPanel: InteractiveLayerInfoPanel[TrackDiffEventEntry] = {
    new InteractiveLayerInfoPanel(this).styled('consolePanel)
  }

  protected def createEntryPanel: TrackDiffEventPanel = new TrackDiffEventPanel(this)
}
