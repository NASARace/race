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
package gov.nasa.race.track

import gov.nasa.race.common.SmoothingVectorExtrapolator
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.{Angle, Length, Speed}


/**
  * represents a tracked object that is updated through (possibly irregular) update messages and whose current
  * state can be estimated from its update history and the current simulation time.
  *
  * This trait mostly encapsulates the estimator algorithm and makes sure observations and estimates
  * can only be read from the outside
  */
trait TrackedObjectEstimator extends Cloneable {

  //--- this is where we store the last observation
  protected var lastObsTime: Long = 0
  protected var _track: Tracked3dObject = null
  def track = _track


  //--- this is what concrete types have to provide
  def lat: Angle
  def lon: Angle
  def altitude: Length
  def heading: Angle
  def speed: Speed

  //--- the public interface
  def addObservation (newTrack: Tracked3dObject): Boolean
  def estimateState (simTimeMillis: Long): Boolean

  override def clone: TrackedObjectEstimator = super.clone.asInstanceOf[TrackedObjectEstimator]

  def estimatedPosition = GeoPosition(lat,lon,altitude)
}

/**
  * an estimator that doesn't estimate - it only reports the last observed position
  */
class HoldEstimator extends TrackedObjectEstimator {
  override def addObservation(obj: Tracked3dObject) = {
    _track = obj
    true
  }

  override def estimateState (simTimeMillis: Long) = {
    //simTimeMillis >= _track.date.getMillis  // FIXME - when raceadapter get sim time right
    true
  }

  override def lat = _track.position.φ
  override def lon = _track.position.λ
  override def altitude = _track.position.altitude
  override def heading = _track.heading
  override def speed = _track.speed
}

/**
  * a TrackedObjectEstimator that uses a SmoothingVectorExtrapolator, which implies a reasonably regular and short update
  * interval and relatively short estimation spans. It is of limited value for transitional track states (rapid maneuvers)
  */
class TrackedObjectExtrapolator extends TrackedObjectEstimator {

  protected val state = new Array[Double](5)
  protected val estimator = new SmoothingVectorExtrapolator(state.length)

  override def lat = Degrees(state(0))
  override def lon = Degrees(state(1))
  override def altitude = Meters(state(2))
  override def heading = Degrees(state(3))
  override def speed = MetersPerSecond(state(4))

  override def addObservation (obs: Tracked3dObject) = {
    val obsMillis = obs.date.toEpochMillis

    // TODO - we should add some consistency checks here
    if (obsMillis > estimator.lastObservationMillis) {
      val pos = obs.position

      state(0) = pos.φ.toDegrees
      state(1) = pos.λ.toDegrees
      state(2) = pos.altitude.toMeters

      state(3) = obs.heading.toDegrees
      state(4) = obs.speed.toMetersPerSecond

      estimator.addObservation(state,obsMillis)

      _track = obs
      true

    } else false
  }

  override def estimateState (simTimeMillis: Long): Boolean = {
    if (simTimeMillis >= estimator.lastObservationMillis) {
      estimator.extrapolate(simTimeMillis, state)
      true
    } else false
  }
}