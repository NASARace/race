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

/**
  * rational polynom interpolation implementing the Bulirsch-Stoer algorithm
  */
class TRatInter (val t0: Long, val ts: Array[Int], val vs: Array[Double]) {
  val N = ts.length
  val N1 = N-1

  val a: Array[Double] = new Array(N)
  val b: Array[Double] = new Array(N)

  def interpolate (t: Long): Double = {
    val EPS = 1e6

    var rPrev = a
    var r = b
    var tiPrev = ts(0)
    var viPrev = vs(0)
    var rkPrev: Double = 0
    rPrev(0) = viPrev

    // TODO - use linear interpolation if there are only 2 observations

    //--- N > 2
    var i = 1
    while (i < N) {
      val ti = ts(i)
      //val vi = vs(i)
      val vi = vs(i)

      //--- k = 0
      r(0) = vi

      if (t == ti) return vi  // observation point
      val dti = (t - ti).toDouble

      //--- k = 1
      var x: Double = (t - tiPrev).toDouble / dti
      var tt = vi - viPrev
      rkPrev = vi + (tt / (x * (1d - tt/vi) - 1d))
      r(1) = rkPrev
      //println(s"r(1) = $rkPrev")

      //--- k > 1
      var k = 2
      while (k <= i) {
        x = (t - ts(i - k)).toDouble / dti
        tt = rkPrev - rPrev(k-1)
        val t2 = rkPrev - rPrev(k-2)

        val rk = rkPrev + (tt / (x * (1d - tt/t2) - 1d))

        r(k) = rk
        rkPrev = rk
        k += 1
      }

      // swap r,rPrev
      val rNext = rPrev
      rPrev = r
      r = rNext

      tiPrev = ti
      viPrev = vi
      i += 1
    }

    rkPrev
  }
}

class DRatInter (val xs: Array[Double], val vs: Array[Double]) {
  val N = xs.length
  val N1 = N-1

  val a: Array[Double] = new Array(N1)
  val b: Array[Double] = new Array(N1)

  def interpolate (xInt: Double): Double = {
    var rPrev = a
    var r = b
    var xiPrev = xs(0)
    var viPrev = vs(0)

    // TODO - use linear interpolation if there are only 2 observations

    //--- N > 2
    var i = 1
    while (i < N) {
      val xi = xs(i)
      val vi = vs(i)
      val dxi = xInt - xi

      if (dxi == 0) return vi  // observation point

      // k = 1
      var x: Double = (xInt - xiPrev) / dxi
      var xx = vi - viPrev
      var rkPrev = vi + (xx / (x * (1d - xx/vi) - 1d))
      r(1) = rkPrev

      var k = 2
      while (k <= i) {
        x = (xInt - xs(i - k)) / dxi
        xx = rkPrev - rPrev(k-1)
        val rk = rkPrev + (xx / (x * (1d - xx / (rkPrev - rPrev(k-2))) - 1d))

        if (k == N1) {
          return rk     // done - we reached the lower right diagonal

        } else {
          r(k) = rk
          rkPrev = rk
          k += 1
        }
      }

      // swap r,rPrev
      val rNext = rPrev
      rPrev = r
      r = rNext

      xiPrev = xi
      viPrev = vi
      i += 1
    }

    return Double.NaN // can't get here
  }
}