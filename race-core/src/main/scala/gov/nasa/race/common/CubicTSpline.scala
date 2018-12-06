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
  * a cubic spline over a parametric function that has a integer time base (e.g. milliseconds)
  * Note - this uses the "not-a-knot" boundary conditions instead of natural splines (C2 = 0) since
  * the latter is not "natural" at all for physical systems and we don't have the first derivatives
  * for the end points.
  * Note also that we try to minimize runtime costs by avoiding temporary arrays during coefficient calculation.
  * See "A Practical Guide to Splines", Carl de Boor, Springer 1978, pg. 57
  *
  * TODO - check if generic T over Float and Double has any runtime costs:
  *   class CubicTSpline[@specialized(Float,Double) T: Fractional] {
  *     val fracOps = implicitly[Fractional[T]]
  *     import fracOps._
  *     ..
  */
class CubicTSpline(val t0: Long, val ts: Array[Int], val c1: Array[Double]) {

  val N = ts.length
  val N1 = N-1

  val c2: Array[Double] = new Array(ts.length)
  val c3: Array[Double] = new Array(ts.length)
  val c4: Array[Double] = new Array(ts.length)

  calcCoefficients

  def evaluateFromTo(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit) = {
    // the bounds are relative to t0
    val tMin = if (tStart < t0) 0 else (tStart - t0).toInt
    val tMax = if (tEnd > (t0 + ts(N1))) ts(N1) else (tEnd - t0).toInt

    var i = getUpperIndex(tMin)
    if (i > 0) {
      var t = tMin
      var ti = ts(i)
      var ti1 = ts(i+1)
      var a1 = c1(i)
      var a2 = c2(i)
      var a3 = c3(i)
      var a4 = c4(i)

      while (t <= tMax) {
        if (t == ti) {
          f(t, a1)
        } else {
          val x = (t - ti)
          val y = a1 + x * (a2 + x * (a3 + x * a4))
          f(t0 + t, y)
        }

        t += dt
        if (t >= ti1 ) { // next spline segment
          i += 1
          ti = ti1
          ti1 = ts(i)
          a1 = c1(i); a2 = c2(i); a3 = c3(i); a4 = c4(i)
        }
      }
    }
  }

  def getUpperIndex (x: Int): Int = {
    if (x > ts(N1)) return -1 // outside interpolated interval

    var i = 0
    while (ts(i) < x) i += 1
    i
  }


  @inline final def squared(x: Double) = x*x

  def calcCoefficients: Unit = {
    // since we are using them so often turn them into locals
    val c2 = this.c2
    val c3 = this.c3
    val c4 = this.c4

    val n = ts.length -1 // max index, not length
    val n1 = n - 1

    var i = 1
    while (i <= n) {
      val c3i = ts(i) - ts(i-1)
      c3(i) = c3i
      c4(i) = (c1(i) - c1(i-1)) / c3i
      i += 1
    }

    // use not-a-knot left boundary conditions
    c4(0) = c3(2)
    c3(0) = c3(1) + c3(2)
    c2(0) = ((c3(1) + 2*c3(0)) * c4(1) * c3(2) + squared(c3(1)) * c4(2)) / c3(0)

    i = 1
    while (i <= n1){
      val g = -c3(i+1)/c4(i-1)
      c2(i) = g*c2(i-1) + 3*(c3(i) * c4(i+1) + c3(i+1) * c4(i))
      c4(i) = g*c3(i-1) + 2*(c3(i) + c3(i+1))
      i += 1
    }

    // use not-a-knot right boundary condition
    var g = c3(n1) + c3(n)
    c2(n) = ((c3(n) +2*g) * c4(n) * c3(n1) + squared(c3(n)) * (c1(n1) - c1(n-2)) / c3(n1)) / g
    g = -g / c4(n1)
    c4(n) = c3(n1)

    c4(n) = g * c3(n1) + c4(n)
    c2(n) = (g * c2(n1) + c2(n)) / c4(n)

    i = n1
    do {
      c2(i) = (c2(i) - c3(i) * c2(i+1)) / c4(i)
      i -= 1
    } while (i > 0)

    i = 1
    while (i <= n) {
      val dtau = c3(i)
      val divdf1 = (c1(i) - c1(i-1)) / dtau
      val divdf3 = c2(i-1) + c2(i) - 2*divdf1
      c3(i-1) = 2* (divdf1 - c2(i-1) - divdf3) / dtau
      c4(i-1) = (divdf3/dtau) * 6/dtau
    }
  }
}
