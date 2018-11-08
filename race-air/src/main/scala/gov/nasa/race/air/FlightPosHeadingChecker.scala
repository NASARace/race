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

package gov.nasa.race.air

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GreatCircle.{finalBearing,distance2D}
import gov.nasa.race.uom.Angle._

import scala.concurrent.duration._

/**
  * a FlightPosChecker that compares reported and computed headings
  * Note this needs to be a class since we instantiate it via reflection
  */
class FlightPosHeadingChecker (config: Config) extends FlightPosChecker {

  val maxHeadingDiff = Degrees(config.getDoubleOrElse("max-heading-diff", 90.0))

  // don't check if pos updates exceed time delta
  val maxTimeDiff = config.getFiniteDurationOrElse("max-dt", 10.seconds).toMillis / 1000.0
  val minTimeDiff = config.getFiniteDurationOrElse("min-dt", 300.milliseconds).toMillis / 1000.0
  val posAccuracy = config.getDoubleOrElse("pos-accuracy", 10.0) // in meters

  // don't check if reported headings indicate flight path change
  val maxHeadingChange = Degrees(config.getDoubleOrElse("max-heading-change", 45.0))

  override def checkPair (fpos: FlightPos, lastFPos: FlightPos): Option[FlightPosProblem] = {
    if (fpos.id != lastFPos.id) { // Ouch - same c/s for different flights
      Some(FlightPosProblem(fpos, lastFPos, s"callsign collision ${fpos.cs} (id1=${fpos.id},id2=${lastFPos.id})"))

    } else {
      val dt = (fpos.date.getMillis - lastFPos.date.getMillis) / 1000.0 // fractional seconds

      if (dt < 0) { // stale position
        Some(FlightPosProblem(fpos, lastFPos, s"stale position for ${fpos.cs} (dt=$dt)"))

      } else if (dt < minTimeDiff) { // ambiguous or redundant
        val compDist = distance(fpos.position, lastFPos.position).toMeters
        val estDist = fpos.speed.toMetersPerSecond * dt
        if (Math.abs(compDist - estDist) > posAccuracy) {
          Some(FlightPosProblem(fpos, lastFPos, s"ambiguous positions for ${fpos.cs} (dt=$dt,ds=$compDist"))
        } else None // redundant (not considered an inconsistency here)

      } else {
        // check if time difference is too great or flight is changing course
        if ((dt < maxTimeDiff) && fpos.heading.within(lastFPos.heading, maxHeadingChange)) {
          val compHeading = finalBearing(lastFPos.position, fpos.position)
          if (!compHeading.within(lastFPos.heading, maxHeadingDiff)) {
            Some(FlightPosProblem(fpos, lastFPos, s"inconsistent heading for ${fpos.cs} (Ψ=$compHeading)"))
          } else None // checks out
        } else None // should not be checked against lastFpos
      }
    }
  }
}
