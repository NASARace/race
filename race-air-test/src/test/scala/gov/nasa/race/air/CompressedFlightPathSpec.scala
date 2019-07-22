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
package gov.nasa.race.air

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.trajectory.MutCompressedTrajectory
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit test for CompactFlightPath
  */
class CompressedFlightPathSpec extends AnyFlatSpec with RaceSpec {

  behavior of "CompactFlightPath"

  "path object" should "reproduce input values" in {
    val positions = Array[(Double,Double,Double,DateTime)](
      ( 37.57251,-122.26521, 1475 , DateTime.parseYMDT("2016-07-03T13:53:17.135") ),
      ( 37.57301,-122.26643, 1450 , DateTime.parseYMDT("2016-07-03T13:53:18.574") ),
      ( 37.57320,-122.26685, 1450 , DateTime.parseYMDT("2016-07-03T13:53:19.098") ),
      ( 37.57333,-122.26720, 1450 , DateTime.parseYMDT("2016-07-03T13:53:19.549") ),
      ( 37.57352,-122.26766, 1450 , DateTime.parseYMDT("2016-07-03T13:53:20.080") ),
      ( 37.57484,-122.27070, 1400 , DateTime.parseYMDT("2016-07-03T13:53:23.812") ),
      ( 37.57535,-122.27193, 1375 , DateTime.parseYMDT("2016-07-03T13:53:25.325") ),
      ( 37.57549,-122.27228, 1375 , DateTime.parseYMDT("2016-07-03T13:53:25.776") ),
      ( 37.57599,-122.27345, 1350 , DateTime.parseYMDT("2016-07-03T13:53:27.283") ),
      ( 37.57650,-122.27461, 1325 , DateTime.parseYMDT("2016-07-03T13:53:28.727") ),
      ( 37.57663,-122.27497, 1325 , DateTime.parseYMDT("2016-07-03T13:53:29.184") ),
      ( 37.57711,-122.27610, 1300 , DateTime.parseYMDT("2016-07-03T13:53:30.566") ),
      ( 37.57729,-122.27652, 1300 , DateTime.parseYMDT("2016-07-03T13:53:31.093") ),
      ( 37.57745,-122.27690, 1275 , DateTime.parseYMDT("2016-07-03T13:53:31.614") ),
      ( 37.57827,-122.27891, 1250 , DateTime.parseYMDT("2016-07-03T13:53:34.172") ),
      ( 37.57846,-122.27932, 1225 , DateTime.parseYMDT("2016-07-03T13:53:34.755") ),
      ( 37.57864,-122.27976, 1225 , DateTime.parseYMDT("2016-07-03T13:53:35.286") ),
      ( 37.57883,-122.28023, 1225 , DateTime.parseYMDT("2016-07-03T13:53:35.870") ),
      ( 37.57911,-122.28094, 1200 , DateTime.parseYMDT("2016-07-03T13:53:36.790") ),
      ( 37.57928,-122.28134, 1200 , DateTime.parseYMDT("2016-07-03T13:53:37.313") ),
      ( 37.57942,-122.28163, 1200 , DateTime.parseYMDT("2016-07-03T13:53:37.705") ),
      ( 37.57957,-122.28207, 1175 , DateTime.parseYMDT("2016-07-03T13:53:38.297") ),
      ( 37.57976,-122.28255, 1175 , DateTime.parseYMDT("2016-07-03T13:53:38.885") ),
      ( 37.58011,-122.28327, 1175 , DateTime.parseYMDT("2016-07-03T13:53:39.871") ),
      ( 37.58057,-122.28438, 1150 , DateTime.parseYMDT("2016-07-03T13:53:41.313") ),
      ( 37.58070,-122.28479, 1125 , DateTime.parseYMDT("2016-07-03T13:53:41.840") ),
      ( 37.58088,-122.28524, 1125 , DateTime.parseYMDT("2016-07-03T13:53:42.428") ),
      ( 37.58106,-122.28559, 1125 , DateTime.parseYMDT("2016-07-03T13:53:42.951") ),
      ( 37.58116,-122.28590, 1125 , DateTime.parseYMDT("2016-07-03T13:53:43.346") ),
      ( 37.58134,-122.28631, 1100 , DateTime.parseYMDT("2016-07-03T13:53:43.871") )
    )

    val path = new MutCompressedTrajectory(2)  // force growth during population
    for (e <- positions) {
      val fpos = new FlightPos("123","X42", GeoPosition.fromDegreesAndMeters(e._1,e._2,e._3),
                               Knots(100.0),Degrees(42), MetersPerSecond(0),e._4)
      path.append(fpos)
    }

    var i = 0
    path.foreach(path.newDataPoint) { p =>
      println(f"$i%2d :  ${p.latDeg}%.5f,${p.lonDeg}%.5f, ${p.altMeters}%.0f, ${p.date.toString}")
      val e = positions(i)

      p.latDeg should be(e._1 +- 0.000001)
      p.lonDeg should be(e._2 +- 0.000001)
      p.altMeters should be(e._3 +- 0.01)
      p.millis should be(e._4.toEpochMillis)
      i += 1
    }
  }
}


