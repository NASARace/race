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
package gov.nasa.race.common

import java.lang.Math.{cos, sin, abs}

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for interpolating T3 iterators
  */
class TInterpolant3Spec extends AnyFlatSpec with RaceSpec {

  @inline def rad(deg: Long): Double = deg.toDouble * Math.PI / 180.0
  @inline def rad(deg: Double): Double = deg * Math.PI / 180.0
  @inline def squared(x: Double): Double = x * x

  val t0 = 1545265794762L
  def t_(t: Long): Long = t * 1000 + t0
  def _t(t: Long): Long = (t-t0)/1000

  def fx (t: Long): Double = 37.6252 + sin(rad(_t(t))) / 100.0
  def fy (t: Long): Double = -122.7865 + cos(rad(_t(t))) / 100.0
  def fz (t: Long): Double = 2675.0 + _t(t)

  "FHT3 iterator" should "iterate through test data with error bounds" in {
    val ts1 = Array[Long]( 0, 12, 31, 40, 45, 48, 55, 62, 69, 74, 80, 83, 90 ).map(t_)
    val data = new SyntheticT3(ts1,fx,fy,fz)
    val r1 = data.interpolateFH
    val r2 = data.interpolateLin

    val tEnd = r1.tRight  // ((r1.tRight + 500) / 1000) * 1000

    val it1 = r1.reverseTailIterator(tEnd, 20000, 2000)
    val it2 = r2.reverseTailIterator(tEnd, 20000, 2000)
    while (it1.hasNext && it2.hasNext){
      val TDataPoint3(t, x1, y1, z1) = it1.next()
      val TDataPoint3(_, x2, y2, z2) = it2.next()

      val vx = fx(t)
      val vy = fy(t)
      val vz = fz(t)

      print(f"$t:")
      print(f"    $x1%10.5f, $y1%10.5f, $z1%4.0f")
      print(f"    $x2%10.5f, $y2%10.5f, $z2%4.0f")
      print(f"    $vx%10.5f, $vy%10.5f, $vz%4.0f\n")
      print(scala.Console.WHITE)
      print("                       ")
      print(f"${abs(vx-x1)}%.4e, ${abs(vy-y1)}%.4e, ${abs(vz-z1)}%4.0f")
      print("    ")
      print(f"${abs(vx-x2)}%.4e, ${abs(vy-y2)}%.4e, ${abs(vz-z2)}%4.0f")
      println(scala.Console.RESET)
    }
  }
}
