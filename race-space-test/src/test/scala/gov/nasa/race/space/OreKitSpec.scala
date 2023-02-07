/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.space

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.trajectory.TDP3
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.{Angle, DateTime, RichUomDouble}
import gov.nasa.race.uom.Time.{Hours, Minutes, Seconds}
import org.scalatest.flatspec.AnyFlatSpec

import scala.language.postfixOps

/**
 * reg test for OreKit library facade
 */
class OreKitSpec extends AnyFlatSpec with RaceSpec {

  def ifOrekitDataFound( f: =>Unit): Unit = {
    executeConditional( OreKit.checkData(), "no orekit-data found, SKIPPING TEST"){ f }
  }

  "OreKit" should "compute a known trajectory with given start and end time" in {
    ifOrekitDataFound {
      // NOAA-20 TLE for 2022-08-05
      val l1 = "1 43013U 17073A   22217.13940738  .00000019  00000-0  29663-4 0  9998"
      val l2 = "2 43013  98.7171 154.9360 0000789  73.0084 287.1179 14.19564203244134"

      val start = DateTime.parseISO("2022-08-05T20:00Z")
      val end = DateTime.parseISO("2022-08-05T21:00Z") // full orbit is about 101 min

      // NOAA 20 on 2022-08-16
      //    val l1 = "1 43013U 17073A   22228.48730038  .00000039  00000-0  39222-4 0  9992"
      //    val l2 = "2 43013  98.7173 166.1084 0000739  90.6346 269.4915 14.19565743245744"
      //    val start = DateTime.parseISO("2022-08-16T19:25Z")
      //    val end = DateTime.parseISO("2022-08-16T21:07Z")  // full orbit is about 101 min

      val dt = Minutes(1)
      //val dt = Seconds(15)

      println(s"\n--- computing trajectory of NOAA-20 between $start and $end")

      val traj = OreKit.getGeoTrajectory(l1, l2, start, dt, end)

      var i = 0
      for (p <- traj.iterator(new TDP3)) {
        println(s"$i: ${p.epochMillis} = ${p.toGenericString3D}")
        i += 1
      }

      assert(traj.size == 61)
      val tp = traj(60)
      assert(tp.date == end)
      expectWithin(tp.position.latDeg, 22.52534, 0.00001)
    }
  }

  "OreKit" should "compute known overpass times for given ground position and min elevation within given time interval" in {
    ifOrekitDataFound {
      // NOAA-20 TLE for 2022-08-15
      val l1 = "1 43013U 17073A   22227.57101136  .00000042  00000-0  40576-4 0  9990"
      val l2 = "2 43013  98.7173 165.2063 0000737  88.8374 271.2887 14.19565592245616"

      val start = DateTime.parseISO("2022-08-15T00:00:00Z")
      val dur = Hours(24)
      val pos = GeoPosition.fromDegrees(37.416, -122.073)

      println(s"\n--- computing known overpasses for min elevation angle")

      val minElevation = Degrees(20)
      val overpasses = OreKit.computeOverpassesWithElevation(l1, l2, start, dur, pos, minElevation, 4)
      println(s"detected ${overpasses.size} overpasses of NOAA-20 for pos $start in $start + ${dur.toHours}h")
      overpasses.foreach(println)

      assert(overpasses.size == 3)
      assert((overpasses(2).date.getJ2000Seconds == DateTime.parseISO("2022-08-15T20:39:18Z").getJ2000Seconds))
    }
  }

  "OreKit" should "compute known overpass times for given ground position and max scan angle within given time interval" in {
    ifOrekitDataFound {
      // NOAA-20 TLE for 2022-08-15
      val l1 = "1 43013U 17073A   22227.57101136  .00000042  00000-0  40576-4 0  9990"
      val l2 = "2 43013  98.7173 165.2063 0000737  88.8374 271.2887 14.19565592245616"

      val start = DateTime.parseISO("2022-08-15T00:00:00Z")
      val dur = Hours(24)
      val pos = GeoPosition.fromDegrees(37.416, -122.073)

      println(s"\n--- computing known overpasses for max scan angle")

      val maxScan = Degrees(56.28) // max scan angle for VIIRS is 56.28, height is ~833km -> swath ~3000km
      val overpasses = OreKit.computeOverpassesWithinScanAngle(l1, l2, start, dur, pos, maxScan, 4)
      println(s"detected ${overpasses.size} overpasses of NOAA-20 for pos $start in $start + ${dur.toHours}h")
      overpasses.foreach(println)

      assert(overpasses.size == 3)
      assert((overpasses(2).date.getJ2000Seconds == DateTime.parseISO("2022-08-15T20:39:18Z").getJ2000Seconds))
    }
  }

  "Orekit" should "compute minElevation bounds from max scan angle and perigee" in {
    ifOrekitDataFound {
      def getElev(tle: TLE, scan: Angle): Angle = {
        val elev = OreKit.getMinElevationForScanAngle(tle, scan)
        println(f"scan= ${scan.toDegrees}%.0f° -> elev= ${elev.toDegrees}%.0f°")
        elev
      }

      val l1 = "1 43013U 17073A   22227.57101136  .00000042  00000-0  40576-4 0  9990"
      val l2 = "2 43013  98.7173 165.2063 0000737  88.8374 271.2887 14.19565592245616"
      val tle = TLE(l1, l2)

      println(s"\n--- min elevation angle from max scan angle")

      val e1 = getElev(tle, 56.28 deg)
      val e2 = getElev(tle, 20 deg)
      val e3 = getElev(tle, 60 deg)

      assert(e2 > e1)
      assert(e3 < e1)
    }
  }

  "Orekit" should "approximate perigee and apogee altitudes for given TLE" in {
    ifOrekitDataFound {
      //val l1 = "1 43013U 17073A   22227.57101136  .00000042  00000-0  40576-4 0  9990"
      //val l2 = "2 43013  98.7173 165.2063 0000737  88.8374 271.2887 14.19565592245616"

      // NOAA 20 on 08/16/2022 - according to space-track.org this should yield 826km and 827km
      val l1 = "1 43013U 17073A   22228.48730038  .00000039  00000-0  39222-4 0  9992"
      val l2 = "2 43013  98.7173 166.1084 0000739  90.6346 269.4915 14.19565743245744"

      val tle = TLE(l1, l2)

      println(s"\n--- approximate perigee and apogee altitudes from TLE")

      val ph = OreKit.estimatePerigeeHeight(tle)
      val ah = OreKit.estimateApogeeHeight(tle)

      println(s"perigee height: ${ph.toKilometers.round}km")
      println(s"apogee height:  ${ah.toKilometers.round}km")
    }
  }

  "Orekit" should "compute overpass periods" in {
    ifOrekitDataFound {
      // roughly all west of the Rockies
      val bounds = Array( // order does not matter
        GeoPosition.fromDegrees(48.9, -125.5),
        GeoPosition.fromDegrees(48.9, -110.7),
        GeoPosition.fromDegrees(31.0, -110.7),
        GeoPosition.fromDegrees(31.0, -117.0),
        GeoPosition.fromDegrees(33.15, -119.616),
        GeoPosition.fromDegrees(40.279, -124.69)
      )

      // NOAA-20 2022-08-17 01:44Z
      val l1 = "1 43013U 17073A   22228.83971922  .00000031  00000-0  35202-4 0  9996"
      val l2 = "2 43013  98.7173 166.4554 0000743  91.3850 268.7411 14.19565781245792"
      val start = DateTime.parseISO("2022-08-16T00:00:00Z")
      val dur = Hours(48)
      val maxScan = Degrees(56.28) // VIIRS

      println("\n--- compute overpass periods")

      val periods = OreKit.computeOverpassPeriods(l1, l2, start, dur, bounds, maxScan)
      periods.foreach { p =>
        println(s"first: ${p.firstDate}   last: ${p.lastDate}   dur: ${p.duration.toMinutes.round}min  coverage: ${p.overpasses.size}/${p.groundPositions.size}")
      }
    }
  }
}
