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

package gov.nasa.race

import gov.nasa.race.common.AssocSeq
import gov.nasa.race.track.{Tracked3dObject, TrackedObjects}
import gov.nasa.race.uom.Angle

/**
  * package `gov.nasa.race.air` contains data definitions, translators, filters, models and actors that are related
  * to airspace monitoring and simulation. It does *not* contain specific visualization support
  *
  * Note that we keep WorldWind based visualization in a separate module
  */
package object air {

  trait TrackedAircraft extends Tracked3dObject {

    def acType: String = "?"

    def flightLevel: Int = ((position.altitude.toFeet)/500).toInt * 5

    def stateString = {
      f"FL${flightLevel}%d ${heading.toDegrees.toInt}%03d° ${speed.toKnots.toInt}%dkn"
    }

    def airline: String = {
      var i=0
      while (i < cs.length) {
        if (cs.charAt(i).isDigit) return cs.substring(0,i)
        i += 1
      }
      "?"
    }
  }

  trait TrackedAircraftSeq[+T <: TrackedAircraft] extends TrackedObjects[T]
}