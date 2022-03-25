/*
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
package gov.nasa.race.ww.track

import java.util.concurrent.atomic.AtomicInteger

import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.VarEuclideanDistanceFilter3D
import gov.nasa.race.track.Tracked3dObject
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.{LayerObject, ViewListener, WWAngle, ViewGoal}
import gov.nasa.worldwind.geom.Position

import scala.collection.immutable.HashSet
import scala.concurrent.duration._

/**
  * a FlightLayer that supports 3D models
  * This includes three additional tasks:
  *
  * (1) loading and accessing a collection of 3D models that can be associated with flights.
  * Since 3D models are quite expensive, the association can be optional, i.e. not every flight
  * might have a 3D model, and models can be re-used for different flight entries
  *
  * (2) maintaining a list of InFlightAircraft objects that are close enough to the eye position
  * to warrant a 3D model display. 3D models are scaled with respect to eyepos distance, i.e. they
  * cannot be used in lieu of flightSymbols (which are fixed in size and attitude). This list
  * changes because of view changes *and* flight position changes. The former requires a bulk
  * computation (over all known flights), and therefore has to be aware of animations (i.e. transient
  * eye position changes)
  *
  * (3) proper update of FlightEntry renderables based on visibility of respective InFlightAircraft
  * objects
  *
  * NOTE - if no model is configured, this should not incur any performance penalties.
  * In particular, eye position and proximity updates should only happen if we have configured models
  */
trait ModelTrackLayer[T <:Tracked3dObject] extends TrackLayer[T] with ViewListener  {

  //--- 3D models and proximities
  val modelDistance = Meters(config.getDoubleOrElse("model-distance",4000.0))
  val eyeDistanceFilter = new VarEuclideanDistanceFilter3D(Angle0,Angle0,Length0, modelDistance)
  var proximities = HashSet.empty[TrackEntry[T]]

  addLayerModels
  val models = config.getConfigSeq("models").map(TrackModel.loadModel[T])

  if (models.nonEmpty) {
    raceViewer.addViewListener(this)  // will update lastEyePos and proximities
  }

  private val pendingUpdates = new AtomicInteger

  // this can be overridden by layers to add their own generic models
  def addLayerModels: Unit = {
    TrackModel.addDefaultModel("defaultAirplane", TrackModel.defaultSpec)
  }

  def getModel (e: TrackEntry[T]): Option[TrackModel[T]] = {
    models.find( _.matches(e.obj) ).orElse(Some(TrackModel.loadDefaultModel))
  }

  // we only get these callbacks if there were configured models
  def viewChanged(viewGoal: ViewGoal): Unit = {
    if (models.nonEmpty) {
      pendingUpdates.getAndIncrement
      delay(500.milliseconds, () => {
        val pos = viewGoal.pos
        val alt = viewGoal.zoom
        eyeDistanceFilter.updateReference(Degrees(pos.latitude.degrees), Degrees(pos.longitude.degrees), Meters(alt))
        if (pendingUpdates.decrementAndGet == 0) recalculateProximities
      })
    }
  }

  def recalculateProximities = foreachTrack(updateProximity)

  def setModel (e: TrackEntry[T]) = {
    val fpos = e.obj
    val m = getModel(e)
    if (m.isDefined) {
      e.setModel(m)
      info(s"set 3D model for $fpos")
      redraw
    } else {
      info (s"no matching/available 3D model for $fpos")
    }
  }

  def unsetModel (e: TrackEntry[T]) = {
    if (e.hasModel) {
      val fpos = e.obj
      e.setModel(None)
      info(s"drop model for $fpos")
      redraw
    }
  }

  def isProximity (e: TrackEntry[T]): Boolean = {
    val fpos = e.obj
    (e.isFocused && (Meters(eyeAltitude) - fpos.position.altitude < modelDistance)) ||
      eyeDistanceFilter.pass(fpos)
  }

  def updateProximity (e: TrackEntry[T]) = {
    if (isProximity(e)) {
      if (!proximities.contains(e)){
        setModel(e)
        proximities = proximities + e
      }
    } else {
      if (proximities.contains(e)) {
        unsetModel(e)
        proximities = proximities - e
      }
    }
  }

  override def addTrackEntryAttributes(e: TrackEntry[T]): Unit = {
    if (models.nonEmpty) updateProximity(e)
    e.addRenderables
  }
  override def updateTrackEntryAttributes(e: TrackEntry[T]): Unit = {
    if (models.nonEmpty) updateProximity(e)
    e.updateRenderables
  }
  override  def releaseTrackEntryAttributes(e: TrackEntry[T]): Unit = {
    e.removeRenderables
  }

  override def setPathContour(lo: LayerObject, cond: Boolean): Unit = {
    setLayerObjectAttribute(lo, _.drawPathContour != cond) { e=>
      if (cond) setModel(e) else unsetModel(e)
      e.setPathContour(cond) }
  }
}
