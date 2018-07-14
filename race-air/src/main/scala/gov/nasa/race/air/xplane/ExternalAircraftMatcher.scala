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

import scala.annotation.tailrec

/**
  * object to map TrackedAircraft instances to X-Plane ExternalAircraft instances
  */
trait ExternalAircraftMatcher {
  def findMatchingIndex (fpos: TrackedAircraft, ea: Array[ExternalAircraft]): Int

  def _findUnassigned (ea: Array[ExternalAircraft]): Int = {
    var i = 0
    while (i < ea.length) {
      if (ea(i).isUnassigned) return i
      i += 1
    }
    -1
  }

  def _find (ea: Array[ExternalAircraft])(f: ExternalAircraft=> Boolean): Int = {
    var i = 0
    while (i < ea.length) {
      val e = ea(i)
      if (e.isUnassigned && f(e)) return i
      i += 1
    }
    -1
  }
}

class FirstUnassignedMatcher extends ExternalAircraftMatcher {
  override def findMatchingIndex (fpos: TrackedAircraft, ea: Array[ExternalAircraft]): Int = _findUnassigned(ea)
}

class FirstLiveryMatcher extends ExternalAircraftMatcher {
  override def findMatchingIndex (fpos: TrackedAircraft, ea: Array[ExternalAircraft]): Int = {
    val liveryName = fpos.airline
    var idx = _find(ea) { e => e.liveryName == liveryName }
    if (idx < 0) idx = _find(ea) { e => e.liveryName == ExternalAircraft.AnyLivery }
    idx
  }
}