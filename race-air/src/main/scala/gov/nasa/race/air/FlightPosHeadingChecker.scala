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
import gov.nasa.race.geo.GreatCircle.finalBearing
import gov.nasa.race.uom.Angle._
import org.joda.time.DateTime

import scala.concurrent.duration._

/**
  * a FlightPosChecker that compares reported and computed headings
  * Note this needs to be a class since we instantiate it via reflection
  */
class FlightPosHeadingChecker (config: Config) extends FlightPosChecker {

  val maxHeadingDiff = Degrees(config.getDoubleOrElse("max-heading-diff", 90.0))

  // don't check if pos updates exceed time delta
  val maxTimeDiff = config.getFiniteDurationOrElse("max-dt", 10.seconds).toMillis

  // don't check if reported headings indicate flight path change
  val maxHeadingChange = Degrees(config.getDoubleOrElse("max-heading-change", 45.0))


  override def checkPair (fpos: FlightPos, lastFPos: FlightPos): Option[FlightPosProblem] = {
    if (isAmbiguousOrStale(fpos.date, lastFPos.date)) { // stale position, ignore
      Some(FlightPosProblem(fpos,lastFPos, s"ambiguous or stale position for ${fpos.cs} (dt = ${(fpos.date.getMillis - lastFPos.date.getMillis)/1000.0}sec)"))
    } else {
      if (isCheckable(fpos,lastFPos)){
        val compHeading = finalBearing(lastFPos.position,fpos.position)
        if (!compHeading.within(lastFPos.heading,maxHeadingDiff)){
          // TODO - we should check dt and ds here to weed out small differences
          Some(FlightPosProblem(fpos,lastFPos,s"inconsistent heading for ${fpos.cs} (is $compHeading)"))
        } else None // checks out
      } else None // should not be checked against lastFpos
    }
  }

  @inline def isAmbiguousOrStale (d1: DateTime, d2: DateTime) = d1.getMillis <= d2.getMillis

  def isCheckable(fpos: FlightPos, lastFpos: FlightPos): Boolean = {
    ((fpos.date.getMillis - lastFpos.date.getMillis) < maxTimeDiff) &&
      (fpos.heading.within(lastFpos.heading,maxHeadingChange))
  }
}
