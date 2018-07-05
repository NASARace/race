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

package gov.nasa.race.air.xplane

import gov.nasa.race.air.TrackedAircraft
import ExternalAircraft._
import scala.annotation.tailrec

/**
  * list of configured external X-Plane aircraft, with back links to related proximity TrackedAircraft objects
  * if entry is in use
  *
  * This X-Plane facing data structure is used to send external aircraft positions to X-Plane, i.e. we have to be able to
  * detect new, changed and removed aircraft positions. The type for a given index never changes since X-Plane
  * currently does not support non-disruptive dynamic loading/unloading of aircraft
  *
  * Note this uses null values for unassigned fpos fields because we otherwise have to create a new Option object each
  * time a position changes. We rather encapsulate all field access here in order to execute updates in constant space
  */
class ExternalAircraftList(entries: Array[ExternalAircraft],
                           onShow: ExternalAircraft=>Unit,
                           onMove: ExternalAircraft=>Unit,
                           onHide: ExternalAircraft=>Unit
                        ) {
  val length = entries.length

  def notEmpty: Boolean = entries.length > 0

  def releaseAll: Unit = foreachAssigned{ e=>
    onHide(e)
    e.fpos = ExternalAircraft.noAircraft
  }

  def assign(fpos: TrackedAircraft): Int = {
    val length1 = length-1

    @tailrec def _allocateNextFree(fpos: TrackedAircraft, idx: Int): Int = {
      val e = entries(idx)
      if (e.fpos eq noAircraft) {
        e.updateObservation(fpos)
        onShow(e)
        idx
      } else {
        if (idx < length1) _allocateNextFree(fpos, idx+1)
        else {
          Thread.dumpStack()
          -1 // cannot happen since FlightsNearList has same size
        }
      }
    }
    _allocateNextFree(fpos,0)
  }

  def set (idx: Int, fpos: TrackedAircraft) = {
    val e = entries(idx)
    e.updateObservation(fpos)
    onMove(e)
  }

  def release (idx: Int) = {
    val e = entries(idx)
    onHide(e)
    e.fpos = null
  }

  // note this can be called frequently, avoid allocation
  @inline def updateEstimates (simTimeMillis: Long) = {
    entries.foreach( e=> if (e.isAssigned) e.updateEstimate(simTimeMillis))
  }

  @inline def foreachChanged (f : ExternalAircraft=>Unit): Unit = {
    entries.foreach( e=> if (e.isAssigned && e.hasChanged) f(e))
  }

  @inline def foreachAssigned(f: ExternalAircraft=>Unit): Unit = {
    entries.foreach( e=> if (e.isAssigned) f(e))
  }

  @inline def foreach(f: ExternalAircraft=>Unit): Unit = {
    entries.foreach(f)
  }
}