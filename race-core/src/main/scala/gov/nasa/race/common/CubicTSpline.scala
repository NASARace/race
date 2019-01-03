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
  * a time based interpolant
  * we store time relative to the first time point, which limits the length of observations
  */
trait TSpline {
  val t0: Long
  val ts: Array[Int]

  val N = ts.length
  val N1 = N-1

  val tEnd = ts(N1)

  def startTime: Long = t0
  def endTime: Long = t0 + ts(N1)

  @inline protected def getTMin (t: Long): Int = if (t < t0) 0 else (t - t0).toInt
  @inline protected def getTMax (t: Long): Int = if (t > (t0 + ts(N1))) ts(N1) else (t - t0).toInt

  @inline protected final def getLeftIndex(t: Int): Int = {
    if (t < 0 || t > ts(N1)) return -1 // outside our interval
    var i = 1
    while (ts(i) <= t) i += 1
    i-1
  }

  @inline protected final def getLeftIndexReverse(t: Int): Int = {
    if (t < 0 || t > ts(N1)) return -1 // outside our interval
    var i = N1
    while (ts(i) > t) i -= 1
    i
  }

  protected final def findLeftIndex (t: Int): Int = {
    if (t < 0) {  // lower than start
      -1
    } else if (t > ts(N1)) {  // higher than end
      N1
    }
    else {
      var a = 0
      var b = N1
      while (b - a > 1){
        val c = (a + b)/2;
        val tc = ts(c);
        if (t == tc) return c
        else if (t > tc) a = c
        else b = c
      }
      a
    }
  }

  /**
    * find highest index with ts[i] <= t by means of bisection
    * assumes ts[] is ordered and strictly monotone
    */
  def findLeftIndexAbsolute (tAbs: Long): Int = findLeftIndex((tAbs - t0).toInt)

}

/**
  * TODO - unify this with FHTInterpolant (which is more expensive for interpolant evaluation
  * but much less so for coefficient calculation)
  *
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
class CubicT1Spline(val t0: Long, val ts: Array[Int], val vs: Array[Double]) extends TSpline {

  // the polynom coefficients
  val a: Array[Double] = vs
  val b: Array[Double] = new Array(N)
  val c: Array[Double] = new Array(N)
  val d: Array[Double] = new Array(N)

  calcCoefficients_nat

  def evaluateFromTo(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double)=>Unit) = {
    // the bounds are relative to t0
    val tMin = getTMin(tStart)
    val tMax = getTMax(tEnd)

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

        while (t >= ti1 && i < N1) {
          ti = ti1
          i += 1

          a = as(i); b = bs(i); c = cs(i); d = ds(i)
          if (i < N1) ti1 = ts(i+1)
        }
      }
    }
  }

  def calcCoefficients_nat: Unit = {
    val ts = this.ts
    val n = ts.length -1 // n intervals, not length

    // use locals instead of fields
    val as = this.a
    val bs = this.b
    val cs = this.c
    val ds = this.d

    var p: Array[Double] = new Array(n)
    var qs: Array[Double] = new Array(n+1)

    var hPrev = ts(1) - ts(0)
    var t = ts(1)
    var tPrev = ts(0)
    var a = as(1)
    var aPrev = as(0)
    var qPrev = 0d
    var i = 1
    while (i < n) {
      val tNext = ts(i+1)
      val aNext = as(i+1)
      val h = tNext - t
      val g = 2.0 * (tNext - tPrev) - hPrev * p(i-1)
      p(i) = h / g
      val q = (3.0 * (aNext * hPrev - a * (tNext - tPrev)+ aPrev * h) / (hPrev * h) - hPrev * qPrev) / g
      qs(i) = q

      i += 1
      hPrev = h
      tPrev = t; t = tNext
      aPrev = a; a = aNext
      qPrev = q
    }

    aPrev = as(n)
    tPrev = ts(n)
    var cPrev = cs(n)
    i = n-1
    while (i >= 0) {
      val t = ts(i)
      val h = tPrev - t
      val a = as(i)

      val c = qs(i) - p(i) * cPrev
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

/**
  * a 2-dimensional time based cubic spline
  */
class CubicT2Spline(val t0: Long, val ts: Array[Int], val xs: Array[Double], val ys: Array[Double]) extends TSpline {

  // polynom coefficients for x values
  val ax: Array[Double] = xs
  val bx: Array[Double] = new Array(N)
  val cx: Array[Double] = new Array(N)
  val dx: Array[Double] = new Array(N)

  // polynom coefficients for y values
  val ay: Array[Double] = ys
  val by: Array[Double] = new Array(N)
  val cy: Array[Double] = new Array(N)
  val dy: Array[Double] = new Array(N)

  calcCoefficients_nat

  def calcCoefficients_nat: Unit = {
    @inline def _computeQ(qPrev: Double, a: Double, aPrev: Double, aNext: Double, tPrev: Int, tNext: Int, h: Int, hPrev: Int, g: Double): Double = {
      (3.0 * (aNext * hPrev - a * (tNext - tPrev)+ aPrev * h) / (hPrev * h) - hPrev * qPrev) / g
    }

    @inline def _computeC (cPrev: Double, q: Double, p: Double): Double = q - p * cPrev
    @inline def _computeB (aPrev: Double, a: Double, cPrev: Double, c: Double, h: Int): Double = {
      (aPrev - a) / h - h * (cPrev + 2.0 * c) / 3.0
    }
    @inline def _computeD (cPrev: Double, c: Double, h: Int): Double = (cPrev - c) / (3.0 * h)


    val ts = this.ts
    val n = ts.length -1 // n intervals, not length

    // use locals instead of fields
    val axs = this.ax;                                  val ays = this.ay
    val bxs = this.bx;                                  val bys = this.by
    val cxs = this.cx;                                  val cys = this.cy
    val dxs = this.dx;                                  val dys = this.dy

    // those only depend on the independent variable (t)
    var p: Array[Double]  = new Array(n)
    var qxs: Array[Double] = new Array(n+1)
    var qys: Array[Double] = new Array(n+1)

    var hPrev = ts(1) - ts(0)
    var t = ts(1)
    var tPrev = ts(0)
    var ax = axs(1);                                    var ay = ays(1)
    var axPrev = axs(0);                                var ayPrev = ays(0)
    var qxPrev = 0d;                                    var qyPrev = 0d
    var i = 1
    while (i < n) {
      val i1 = i + 1
      val tNext = ts(i1)
      val axNext = axs(i1);                             val ayNext = ays(i1)
      val h = tNext - t
      val g = 2.0 * (tNext - tPrev) - hPrev * p(i-1)
      p(i) = h / g

      val qx = _computeQ( qxPrev, ax, axPrev, axNext, tPrev, tNext, h, hPrev, g)
      val qy = _computeQ( qyPrev, ay, ayPrev, ayNext, tPrev, tNext, h, hPrev, g)
      qxs(i) = qx;                                      qys(i) = qy

      i += 1
      hPrev = h
      tPrev = t; t = tNext
      axPrev = ax; ax = axNext;                         ayPrev = ay; ay = ayNext
      qxPrev = qx;                                      qyPrev = qy
    }

    axPrev = axs(n);                                    ayPrev = ays(n)
    tPrev = ts(n)
    var cxPrev = cxs(n);                                var cyPrev = cys(n)
    i = n-1
    while (i >= 0) {
      val t = ts(i)
      val h = tPrev - t

      val m = p(i)
      val ax = axs(i);                                  val ay = ays(i)
      val cx = _computeC(cxPrev, qxs(i), m);            val cy = _computeC(cyPrev, qys(i), m)

      cxs(i) = cx;                                      cys(i) = cy
      bxs(i) = _computeB(axPrev, ax, cxPrev, cx, h);    bys(i) = _computeB(ayPrev, ay, cyPrev, cy, h)
      dxs(i) = _computeD(cxPrev, cx, h);                dys(i) = _computeD(cyPrev, cy, h)

      i -= 1
      tPrev = t
      axPrev = ax;                                      ayPrev = ay
      cxPrev = cx;                                      cyPrev = cy
    }
  }

  def evaluateFromTo(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double)=>Unit) = {

    @inline def _computeV (t: Int, a: Double, b: Double, c: Double, d: Double): Double = a + t*(b + t*(c + t*d))

    // the bounds are relative to t0
    val tMin = getTMin(tStart)
    val tMax = getTMax(tEnd)

    var i = getLeftIndex(tMin)
    if (i >= 0) {
      val ts = this.ts
      val axs = this.ax;                                val ays = this.ay
      val bxs = this.bx;                                val bys = this.by
      val cxs = this.cx;                                val cys = this.cy
      val dxs = this.dx;                                val dys = this.dy

      var t = tMin
      var ti = ts(i)
      var ti1 = ts(i+1)

      var ax = axs(i);                                  var ay = ays(i)
      var bx = bxs(i);                                  var by = bys(i)
      var cx = cxs(i);                                  var cy = cys(i)
      var dx = dxs(i);                                  var dy = dys(i)

      while (t <= tMax) {
        if (t == ti) {
          f(t, ax, ay)
        } else {
          val s = (t - ti)
          val vx = _computeV(s, ax, bx, cx, dx);        val vy = _computeV(s, ay, by, cy, dy)

          f(t0 + t, vx, vy)
        }

        t += dt

        while (t >= ti1 && i < N1) {
          ti = ti1
          i += 1

          ax = axs(i);                                 ay = ays(i)
          bx = bxs(i);                                 by = bys(i)
          cx = cxs(i);                                 cy = cys(i)
          dx = dxs(i);                                 dy = dys(i)

          if (i < N1) ti1 = ts(i+1)
        }
      }
    }
  }
}


/**
  * a 3-dimensional time based cubic spline
  */
class CubicT3Spline(val t0: Long, val ts: Array[Int], val xs: Array[Double], val ys: Array[Double], val zs: Array[Double]) extends TSpline {

  // polynom coefficients for x values
  val ax: Array[Double] = xs
  val bx: Array[Double] = new Array(N)
  val cx: Array[Double] = new Array(N)
  val dx: Array[Double] = new Array(N)

  // polynom coefficients for y values
  val ay: Array[Double] = ys
  val by: Array[Double] = new Array(N)
  val cy: Array[Double] = new Array(N)
  val dy: Array[Double] = new Array(N)

  // polynom coefficients for y values
  val az: Array[Double] = zs
  val bz: Array[Double] = new Array(N)
  val cz: Array[Double] = new Array(N)
  val dz: Array[Double] = new Array(N)

  calcCoefficients_nat

  def calcCoefficients_nat: Unit = {
    @inline def _computeQ(qPrev: Double, a: Double, aPrev: Double, aNext: Double, tPrev: Int, tNext: Int, h: Int, hPrev: Int, g: Double): Double = {
      (3.0 * (aNext * hPrev - a * (tNext - tPrev)+ aPrev * h) / (hPrev * h) - hPrev * qPrev) / g
    }

    @inline def _computeC (cPrev: Double, q: Double, p: Double): Double = q - p * cPrev
    @inline def _computeB (aPrev: Double, a: Double, cPrev: Double, c: Double, h: Int): Double = {
      (aPrev - a) / h - h * (cPrev + 2.0 * c) / 3.0
    }
    @inline def _computeD (cPrev: Double, c: Double, h: Int): Double = (cPrev - c) / (3.0 * h)


    val ts = this.ts
    val n = ts.length -1 // n intervals, not length

    // use locals instead of fields
    val axs = this.ax;                                  val ays = this.ay;      val azs = this.az
    val bxs = this.bx;                                  val bys = this.by;      val bzs = this.bz
    val cxs = this.cx;                                  val cys = this.cy;      val czs = this.cz
    val dxs = this.dx;                                  val dys = this.dy;      val dzs = this.dz

    // those only depend on the independent variable (t)
    var p: Array[Double]  = new Array(n)
    var qxs: Array[Double] = new Array(n+1);   var qys: Array[Double] = new Array(n+1); var qzs: Array[Double] = new Array(n+1)


    var hPrev = ts(1) - ts(0)
    var t = ts(1)
    var tPrev = ts(0)
    var ax = axs(1);                                    var ay = ays(1);        var az = azs(1)
    var axPrev = axs(0);                                var ayPrev = ays(0);    var azPrev = azs(0)
    var qxPrev = 0d;                                    var qyPrev = 0d;        var qzPrev = 0d
    var i = 1
    while (i < n) {
      val i1 = i + 1
      val tNext = ts(i1)
      val axNext = axs(i1);                             val ayNext = ays(i1);   val azNext = azs(i1)
      val h = tNext - t
      val g = 2.0 * (tNext - tPrev) - hPrev * p(i-1)
      p(i) = h / g

      val qx = _computeQ( qxPrev, ax, axPrev, axNext, tPrev, tNext, h, hPrev, g)
      val qy = _computeQ( qyPrev, ay, ayPrev, ayNext, tPrev, tNext, h, hPrev, g)
      val qz = _computeQ( qzPrev, az, azPrev, azNext, tPrev, tNext, h, hPrev, g)

      qxs(i) = qx;                                      qys(i) = qy;            qzs(i) = qz

      i += 1
      hPrev = h
      tPrev = t; t = tNext
      axPrev = ax; ax = axNext;                         ayPrev = ay; ay = ayNext;     azPrev = az; az = azNext
      qxPrev = qx;                                      qyPrev = qy;                  qzPrev = qz
    }

    axPrev = axs(n);                                    ayPrev = ays(n);              azPrev = azs(n)
    tPrev = ts(n)
    var cxPrev = cxs(n);                                var cyPrev = cys(n);          var czPrev = czs(n)
    i = n-1
    while (i >= 0) {
      val t = ts(i)
      val h = tPrev - t

      val m = p(i)
      val ax = axs(i);                                  val ay = ays(i);              val az = azs(i)
      val cx = _computeC(cxPrev, qxs(i), m);            val cy = _computeC(cyPrev, qys(i), m);  val cz = _computeC(czPrev, qzs(i), m)

      cxs(i) = cx;                                      cys(i) = cy;                  czs(i) = cz
      bxs(i) = _computeB(axPrev, ax, cxPrev, cx, h);    bys(i) = _computeB(ayPrev, ay, cyPrev, cy, h);  bzs(i)= _computeB(azPrev, az, czPrev, cz, h)
      dxs(i) = _computeD(cxPrev, cx, h);                dys(i) = _computeD(cyPrev, cy, h); dzs(i) = _computeD(czPrev, cz, h)

      i -= 1
      tPrev = t
      axPrev = ax;                                      ayPrev = ay;                  azPrev = az
      cxPrev = cx;                                      cyPrev = cy;                  czPrev = cz
    }
  }

  def evaluateFromTo(tStart: Long, tEnd: Long, dt: Int)(f: (Long,Double,Double,Double)=>Unit) = {

    @inline def _computeV (t: Int, a: Double, b: Double, c: Double, d: Double): Double = a + t*(b + t*(c + t*d))

    // the bounds are relative to t0
    val tMin = getTMin(tStart)
    val tMax = getTMax(tEnd)

    var i = getLeftIndex(tMin)
    if (i >= 0) {
      val ts = this.ts
      val axs = this.ax;                                val ays = this.ay;            val azs = this.az
      val bxs = this.bx;                                val bys = this.by;            val bzs = this.bz
      val cxs = this.cx;                                val cys = this.cy;            val czs = this.cz
      val dxs = this.dx;                                val dys = this.dy;            val dzs = this.dz

      var t = tMin
      var ti = ts(i)
      var ti1 = ts(i+1)

      var ax = axs(i);                                  var ay = ays(i);              var az = azs(i)
      var bx = bxs(i);                                  var by = bys(i);              var bz = bzs(i)
      var cx = cxs(i);                                  var cy = cys(i);              var cz = czs(i)
      var dx = dxs(i);                                  var dy = dys(i);              var dz = dzs(i)

      while (t <= tMax) {
        if (t == ti) {
          f(t, ax, ay, az)
        } else {
          val s = (t - ti)
          val vx = _computeV(s, ax, bx, cx, dx);        val vy = _computeV(s, ay,by,cy,dy);  val vz = _computeV(s, az,bz,cz,dz)

          f(t0 + t, vx, vy, vz)
        }

        t += dt

        while (t >= ti1 && i < N1) {
          ti = ti1
          i += 1

          ax = axs(i);                                  ay = ays(i);                  az = azs(i)
          bx = bxs(i);                                  by = bys(i);                  bz = bzs(i)
          cx = cxs(i);                                  cy = cys(i);                  cz = czs(i)
          dx = dxs(i);                                  dy = dys(i);                  dz = dzs(i)

          if (i < N1) ti1 = ts(i+1)
        }
      }
    }
  }
}

