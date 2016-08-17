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

package gov.nasa.race.actors.bridges.xplane

import com.typesafe.config.Config
import gov.nasa.race.data.{FlightPos, SmoothingExtrapolator}

import scala.annotation.tailrec

/**
  * object representing a single external aircraft loaded by X-Plane
  * we use this to (1) initialize X-Plane configuration and (2) send external plane position updates to it
  */
class ACEntry (val idx: Int,                   // X-Plane aircraft index
               val acType: String,             // aircraft type information used to find the best match for a real aircraft
               var fpos: FlightPos=null) {     // last reported aircraft position

  var isVisible = false
  var hideCount = 3

  //--- estimators
  val latEstimator = new SmoothingExtrapolator
  val lonEstimator = new SmoothingExtrapolator
  val altEstimator = new SmoothingExtrapolator
  val psiEstimator = new SmoothingExtrapolator

  private def estimate(estimator: SmoothingExtrapolator, simMillis: Long) = if (isVisible) estimator.extrapolate(simMillis) else 0.0

  // the estimated values
  var latDeg: Double = 0
  var lonDeg: Double = 0
  var altMeters: Double = 0

  var psiDeg: Float = 0f
  var thetaDeg: Float = 0f
  var phiDeg: Float = 0f

  var gear: Float = 0
  var flaps: Float = 0
  var throttle: Float = 0

  /**
    * called whenever we receive a new FlightPos from RACE (irregular time intervals)
    */
  def updateFpos (newFpos: FlightPos) = {
    fpos = newFpos
    val simTime = newFpos.date.getMillis

    latEstimator.addObservation(fpos.position.φ.toDegrees, simTime)
    lonEstimator.addObservation(fpos.position.λ.toDegrees, simTime)
    altEstimator.addObservation(fpos.altitude.toMeters, simTime)
    psiEstimator.addObservation(fpos.heading.toDegrees, simTime)

    if (!isVisible) isVisible = true
  }

  def isRelevant: Boolean = {
    if (isVisible) true
    else {
      if (hideCount > 0) hideCount -= 1
      hideCount > 0
    }
  }

  def hide = {
    isVisible = false
    hideCount = 3
    fpos = null

    latEstimator.reset
    lonEstimator.reset
    altEstimator.reset
    psiEstimator.reset

    /*
    latDeg = 0; lonDeg = 0; altMeters = 0
    psiDeg = 0f; thetaDeg = 0f; phiDeg = 0f
    gear = 0f; flaps = 0f; throttle = 0f
    */
    altMeters = 100000
  }

  /**
    * called before we send position updates to X-Plane (at configured time intervals)
    */
  def updateEstimates (simTimeMillis: Long) = {
    if (isVisible) {
      latDeg = estimate(latEstimator, simTimeMillis)
      lonDeg = estimate(lonEstimator, simTimeMillis)
      altMeters = estimate(altEstimator, simTimeMillis)
      psiDeg = estimate(psiEstimator, simTimeMillis).toFloat
      // the rest we don't touch anyways
    }
  }
}

/**
  * list of configured external X-Plane aircraft, with back links to related proximity FlightPos objects if entry is in use
  *
  * This X-Plane facing data structure is used to send external aircraft positions to X-Plane, i.e. we have to be able to
  * detect new, changed position and removed aircraft positions. The type for a given index never changes since X-Plane
  * currently does not support non-disruptive dynamic loading/unloading of aircraft
  *
  * X-Plane updates happen at fixed (configured) time intervals
  *
  * Note this uses null values for unassigned fpos fields because we otherwise have to create a new Option object each
  * time a position changes. We rather encapsulate all field access here in order to execute updates in constant space
  */
class XPlaneAircraftList(acConfigs: Seq[Config],
                         onShow: (ACEntry)=>Unit, onMove: (ACEntry)=>Unit, onHide: (ACEntry)=>Unit) {

  val length = acConfigs.size
  val entries = new Array[ACEntry](length)

  for ((acConf,idx) <- acConfigs.zipWithIndex) entries(idx) = new ACEntry(idx+1, acConf.getString("type"))

  def assign(fpos: FlightPos): Int = {
    @tailrec def _allocateNextFree(fpos: FlightPos, idx: Int): Int = {
      val e = entries(idx)
      if (e.fpos == null) {
        e.updateFpos(fpos)
        onShow(e)
        idx
      } else {
        if (idx < length-1) _allocateNextFree(fpos, idx+1)
        else {
          Thread.dumpStack()
          -1 // cannot happen since FlightsNearList has same size
        }
      }
    }
    _allocateNextFree(fpos,0)
  }

  def set (idx: Int, fpos: FlightPos) = {
    val e = entries(idx)
    e.updateFpos(fpos)
    onMove(e)
  }

  def release (idx: Int) = {
    val e = entries(idx)
    onHide(e)
    e.fpos = null
  }

  // note this can be called frequently, avoid allocation
  def updateEstimates (simTimeMillis: Long) = {
    @tailrec def _updateEstimates (i: Int): Unit = {
      if (i < length) {
        entries(i).updateEstimates(simTimeMillis)
        _updateEstimates(i+1)
      }
    }
    _updateEstimates(0)
  }
}