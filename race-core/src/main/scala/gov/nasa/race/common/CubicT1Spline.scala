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
  * a cubic spline over a parametric function that has a integer time parameter (e.g. milliseconds)
  * this implementation uses 'natural' splines, i.e. P''' = 0 in both end points
  * we minimize field and array access in order to speed up computation
  *
  * TODO - check if generic T over Float and Double has any runtime costs:
  *   class CubicTSpline[@specialized(Float,Double) T: Fractional] {
  *     val fracOps = implicitly[Fractional[T]]
  *     import fracOps._
  *     ..
  */
class CubicT1Spline(val t0: Long, val ts: Array[Int], val vs: Array[Double]) {

  val N = ts.length
  val N1 = N-1

  // the polynom coefficients
  val a: Array[Double] = vs
  val b: Array[Double] = new Array(N)
  val c: Array[Double] = new Array(N)
  val d: Array[Double] = new Array(N)

  calcCoefficients_nat

  def startTime: Long = t0
  def endTime: Long = t0 + ts(N1)

  def evaluateFromTo(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit) = {
    // the bounds are relative to t0
    val tMin = if (tStart < t0) 0 else (tStart - t0).toInt
    val tMax = if (tEnd > (t0 + ts(N1))) ts(N1) else (tEnd - t0).toInt

    var i = getLeftIndex(tMin)
    if (i >= 0) {
      val ts = this.ts
      val as = this.a
      val bs = this.b
      val cs = this.c
      val ds = this.d

      var t = tMin
      var ti = ts(i)
      var ti1 = ts(i+1)
      var a = as(i)
      var b = bs(i)
      var c = cs(i)
      var d = ds(i)

      while (t <= tMax) {
        if (t == ti) {
          f(t, a)
        } else {
          val x = (t - ti)
          val y = a + x*(b + x*(c + x*d))

          f(t0 + t, y)
        }

        t += dt

        if (t >= ti1) {
          ti = ti1
          i += 1
          if (i < N){
            a = as(i); b = bs(i); c = cs(i); d = ds(i)
            if (i < N1) ti1 = ts(i+1)
          }
        }
      }
    }
  }

  @inline protected final def getLeftIndex(x: Int): Int = {
    if (x < 0 || x > ts(N1)) return -1 // outside our interval
    var i = 1
    while (ts(i) <= x) i += 1
    i-1
  }

  @inline final def squared(x: Double) = x*x


  def calcCoefficients_nat: Unit = {
    val ts = this.ts
    val n = ts.length -1 // n intervals, not length

    // use locals instead of fields
    val as = this.a
    val bs = this.b
    val cs = this.c
    val ds = this.d

    var mu: Array[Double] = new Array(n)
    var zs: Array[Double] = new Array(n+1)

    var hPrev = ts(1) - ts(0)
    var t = ts(1)
    var tPrev = ts(0)
    var a = as(1)
    var aPrev = as(0)
    var zPrev = 0d
    var i = 1
    while (i < n) {
      val tNext = ts(i+1)
      val aNext = as(i+1)
      val h = tNext - t
      val g = 2.0 * (tNext - tPrev) - hPrev * mu(i-1)
      mu(i) = h / g
      var z = (3.0 * (aNext * hPrev - a * (tNext - tPrev)+ aPrev * h) / (hPrev * h) - hPrev * zPrev) / g
      zs(i) = z

      i += 1
      hPrev = h
      tPrev = t; t = tNext
      aPrev = a; a = aNext
      zPrev = z
    }

    aPrev = as(n)
    tPrev = ts(n)
    var cPrev = cs(n)
    i = n-1
    while (i >= 0) {
      val t = ts(i)
      val h = tPrev - t
      val a = as(i)

      val c = zs(i) - mu(i) * cPrev
      cs(i) = c
      bs(i) = (aPrev - a) / h - h * (cPrev + 2.0 * c) / 3.0
      ds(i) = (cPrev - c) / (3.0 * h)

      i -= 1
      aPrev = a
      cPrev = c
      tPrev = t
    }
  }
}
