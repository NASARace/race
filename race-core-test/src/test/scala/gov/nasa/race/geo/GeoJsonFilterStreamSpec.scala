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
package gov.nasa.race.geo

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import java.io.ByteArrayInputStream

/**
  * unit test for GeoJsonStreamFilter
  */
class GeoJsonFilterStreamSpec extends AnyFlatSpec with RaceSpec {

  val data ="""
{
  "type":"FeatureCollection",
  "features":
  [
    {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-114.127454,34.265674],[-114.127454,34.265674]]]},"properties":{"release":1}},
    {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-114.127694,34.260939],[-114.127757,34.261044],[-114.127694,34.260939]]]},"properties":{"release":1}},
    {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-124.335791,40.461798],[-124.335791,40.461798]]]},"properties":{"release":2}},
    {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-124.347446,40.542901],[-124.347446,40.542901]]]},"properties":{"release":2}} ,

    {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-114.123456,30.123456],[-114.123459,30.123459]]]},"properties":{"release":2}},

  ]
}"""

  "a GeoJsonStreamFilter" should "filter a known FeatureCollection" in {
    def filter (pos: GeoPosition): Boolean = pos.lonDeg > -120.0

    val bai = new ByteArrayInputStream(data.getBytes)
    val gjs = new GeoJsonFilterStream(bai, filter)
    val is = gjs

    val sb = new StringBuffer()
    var b: Int = is.read()
    while (b != -1) {
      sb.append(b.toChar)
      b = is.read()
    }

    println("--- collected output:")
    println(sb)
  }

}
