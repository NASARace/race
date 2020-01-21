/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.air.filter

import com.typesafe.config.Config
import gov.nasa.race.air.{Airport, TrackedAircraft, TrackedAircraftSeq}
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.geo.{Euclidean, GeoPosition}
import gov.nasa.race.uom.{Angle, Length, Speed}


/**
  * a high volume filter to detect aircraft that could be approach candidates
  *
  * note this is a state-less filter. Any state-based processing (e.g. check for negative vr) should be in the client
  */
class ApproachFilter (val center: GeoPosition,
                      val radius: Length,
                      val minSlope: Angle, val maxSlope: Angle, // normal slope is 3-10deg
                      val minSpeed: Speed, val maxSpeed: Speed, // approach/landing speed bounds
                      val maxVr: Speed, // max vertical rate (allow for temp upwind)
                      val maxDeviation: Angle, // between reported heading and center bearing
                      val finalRadius: Length, // from where aircraft has to align with runway
                      val maxFinalSpeed: Speed, // max speed for final approach
                      val maxFinalDeviation: Angle, // max deviation from runway heading
                      val maxFinalSlope: Angle, // max slope for final approach
                      val rwyHeading: Angle, // heading of runway (optional)
                      val config: Config=null) extends ConfigurableFilter {

  def this (conf: Config) = this(Airport.allAirports(conf.getString("airport")).position,
    conf.getLengthOrElse("radius", NauticalMiles(80)),
    conf.getAngleOrElse("min-slope", Degrees(1.5)),
    conf.getAngleOrElse("max-slope", Degrees(12)),
    conf.getSpeedOrElse( "min-speed", Knots(120)),
    conf.getSpeedOrElse( "max-speed", Knots(300)),
    conf.getSpeedOrElse("max-vr", FeetPerSecond(20)),
    conf.getAngleOrElse("max-deviation", Degrees(90)),
    conf.getLengthOrElse("final-radius", NauticalMiles(5)),
    conf.getSpeedOrElse("max-final-speed", Knots(200)),
    conf.getAngleOrElse("max-final-deviation", Degrees(10)),
    conf.getAngleOrElse("max-final-slope", Degrees(8)),
    conf.getAngleOrElse("rwy",UndefinedAngle),  // needs to be configured
    conf)


  override def pass (o: Any): Boolean = {
    o match {
      case ac: TrackedAircraft => isApproachCandidate(ac)
      case _ => false
    }
  }

  def isApproachCandidate (ac: TrackedAircraft): Boolean = {
    val acPos = ac.position
    val d = Euclidean.distance2D(center,acPos)

    if (d < radius){                                        // near enough
      val bearing = Euclidean.heading(acPos, center)
      val acHeading = ac.heading
      val acSpeed = ac.speed

      val h = acPos.altitude - center.altitude
      val slope = Radians(Math.atan(h/d))

      def _isApproach(minRefSlope: Angle, maxRefSlope: Angle, maxRefSpeed: Speed, maxRefVr: Speed, maxRefDeviation: Angle): Boolean = {
        (slope > minRefSlope && slope < maxRefSlope) &&
          (acSpeed > minSpeed && acSpeed < maxRefSpeed) &&
          (ac.vr < maxRefVr) &&
          (absDiff(bearing, acHeading) < maxRefDeviation)
      }

      if (d < finalRadius) {
        if (rwyHeading.isUndefined || absDiff(rwyHeading,acHeading) < maxFinalDeviation) {
          if (d < Meters(3000)) { // bearing and altitude not accurate enough anymore
            _isApproach(Angle0, Degrees(20), maxFinalSpeed, Speed0, Degrees(30))
          } else {
            _isApproach(minSlope,maxFinalSlope, maxFinalSpeed, Speed0, maxFinalDeviation)
          }
        } else false
      } else {
        _isApproach(minSlope,maxSlope,maxSpeed,maxVr,maxDeviation)
      }

    } else {
      false
    }
  }
}
