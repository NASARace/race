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
class CubicT1Spline(val t0: Long, val ts: Array[Int], val c1: Array[Double]) {

  val N = ts.length
  val N1 = N-1

  val c2: Array[Double] = new Array(N)
  val c3: Array[Double] = new Array(N)
  val c4: Array[Double] = new Array(N)

  calcCoefficients_nat

  def startTime: Long = t0
  def endTime: Long = t0 + ts(N1)

  def evaluateFromTo(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit) = {
    // the bounds are relative to t0
    val tMin = if (tStart < t0) 0 else (tStart - t0).toInt
    val tMax = if (tEnd > (t0 + ts(N1))) ts(N1) else (tEnd - t0).toInt

    var i = getIndex(tMin)
    if (i >= 0) {
      var t = tMin
      var ti = ts(i)
      var ti1 = ts(i+1)
      var a1 = c1(i)
      var a2 = c2(i)
      var a3 = c3(i)
      var a4 = c4(i)

      while (t <= tMax) {
        //print(s"@@ $i: ti=$ti t=$t")
        if (t == ti) {
          //println(s"  => $a1")
          f(t, a1)
        } else {
          val x = (t - ti)
          val y = a1 + x*(a2 + x*(a3 + x*a4))

          //println(s" dt=$x => $y")
          f(t0 + t, y)
        }

        t += dt

        if (t >= ti1) {
          ti = ti1
          i += 1
          if (i < N){
            a1 = c1(i)
            a2 = c2(i)
            a3 = c3(i)
            a4 = c4(i)
            if (i < N1) ti1 = ts(i+1)
          }
        }
      }
    }
  }


  @inline protected final def getIndex (x: Int): Int = {
    if (x < 0 || x > ts(N1)) return -1 // outside our interval
    var i = 1
    while (ts(i) <= x) i += 1
    i-1
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

    // TODO check for N > 2 here..
    // use not-a-knot left boundary conditions
    c4(0) = c3(2)
    c3(0) = c3(1) + c3(2)
    c2(0) = ((c3(1) + 2d*c3(0)) * c4(1) * c3(2) + squared(c3(1)) * c4(2)) / c3(0)

    i = 1
    while (i <= n1){
      val g = -c3(i+1)/c4(i-1)
      c2(i) = g*c2(i-1) + 3d*(c3(i) * c4(i+1) + c3(i+1) * c4(i))
      c4(i) = g*c3(i-1) + 2d*(c3(i) + c3(i+1))
      i += 1
    }

    // TODO check for N == 3 here..
    // use not-a-knot right boundary condition
    var g = c3(n1) + c3(n)
    c2(n) = ((c3(n) +2d*g) * c4(n) * c3(n1) + squared(c3(n)) * (c1(n1) - c1(n-2)) / c3(n1)) / g
    g = -g / c4(n1)
    c4(n) = c3(n1)

    c4(n) = g * c3(n1) + c4(n)
    c2(n) = (g * c2(n1) + c2(n)) / c4(n)

    i = n1
    do {
      c2(i) = (c2(i) - c3(i) * c2(i+1)) / c4(i)
      i -= 1
    } while (i >= 0)

    i = 1
    while (i <= n) {
      val dtau = c3(i)
      val divdf1 = (c1(i) - c1(i-1)) / dtau
      val divdf3 = c2(i-1) + c2(i) - 2d*divdf1
      c3(i-1) = 2d* (divdf1 - c2(i-1) - divdf3) / dtau
      c4(i-1) = (divdf3/dtau) * 6d/dtau
      i += 1
    }
  }

  def calcCoefficients_nat: Unit = {
    val n = ts.length -1 // n intervals, not length
    val y = c1
    val b = c2
    val c = c3
    val d = c4

    var mu: Array[Double] = new Array(n)
    var z: Array[Double] = new Array(n+1)

    var hPrev = ts(1) - ts(0)
    var t = ts(1)
    var tPrev = ts(0)
    var i = 1
    while (i < n) {
      val tNext = ts(i+1)
      val h = tNext - t
      val g = 2.0 * (tNext - tPrev) - hPrev * mu(i-1)
      mu(i) = h / g
      z(i) = (3.0 * (y(i+1) * hPrev - y(i) * (tNext - tPrev)+ y(i-1) * h) / (hPrev * h) - hPrev * z(i-1)) / g
      i += 1
      hPrev = h
      tPrev = t
      t = tNext
    }

    var j = n-1
    while (j >= 0) {
      val h = ts(j+1) - ts(j)
      c(j) = z(j) - mu(j) * c(j + 1)
      b(j) = (y(j+1) - y(j)) / h - h * (c(j+1) + 2.0 * c(j)) / 3.0
      d(j) = (c(j+1) - c(j)) / (3.0 * h)
      j -= 1
    }
  }
}
