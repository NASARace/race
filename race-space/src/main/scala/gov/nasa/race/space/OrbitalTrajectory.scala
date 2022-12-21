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

import gov.nasa.race.common.{Cartesian3, MutXyz, Xyz, maxd, squared}
import gov.nasa.race.geo.{Datum, MutXyzPos, angularDistance, degPerMeridianMeter}
import gov.nasa.race.uom.{DateTime, Length, Time}
import org.hipparchus.geometry.euclidean.threed.Vector3D

import java.lang.Math._

object OrbitalTrajectory {
  def from (a: Array[Xyz], tStart: DateTime, dt: Time): OrbitalTrajectory = {
    val trj = new OrbitalTrajectory(a.length, tStart, dt)
    var i = 0
    while (i < a.length) {
      trj(i) = a(i)
      i += 1
    }
    trj
  }
}

/**
 * simple container to store ecef coordinates of satellite trajectory
 * note this assumes a regular time series
 */
class OrbitalTrajectory(val length: Int, val tStart: DateTime, val dt: Time) {

  val x: Array[Double] = new Array[Double](length)
  val y: Array[Double] = new Array[Double](length)
  val z: Array[Double] = new Array[Double](length)

  val tEnd: DateTime = tStart + (dt * length)

  def size: Int = length

  def apply (i: Int): Cartesian3 = Xyz( x(i), y(i), z(i))

  def update(i: Int, vec3: Vector3D): Unit = {
    x(i) = vec3.getX
    y(i) = vec3.getY
    z(i) = vec3.getZ
  }

  def update(i: Int, p: Cartesian3): Unit = {
    x(i) = p.x
    y(i) = p.y
    z(i) = p.z
  }

  def setToGroundTrack (i: Int, vec3: Vector3D): Unit = {
    val p = MutXyz( vec3.getX, vec3.getY, vec3.getZ)
    Datum.scaleToEarthRadius(p)
    x(i) = p.x
    y(i) = p.y
    z(i) = p.z
  }

  def toGroundTrack(): Unit = {
    val p = MutXyz()
    var i=0
    while (i<length) {
      p.setTo(x(i), y(i), z(i))
      Datum.scaleToEarthRadius(p)
      x(i) = p.x
      y(i) = p.y
      z(i) = p.z

      i += 1
    }
  }

  def t(i: Int): DateTime = tStart + (dt * i)

  def getFirstDate: DateTime = tStart
  def getLastDate: DateTime = tEnd

  def foreach( f: (DateTime,Cartesian3)=> Unit): Unit = {
    val p = new MutXyz()
    var i = 0
    var d = tStart
    while (i < length) {
      p.set( x(i), y(i), z(i))

      f(d, p)

      i += 1
      d += dt
    }
  }

  @inline final def dist2 (i: Int, p: Cartesian3): Double = {
    val dx = x(i) - p.x
    val dy = y(i) - p.y
    val dz = z(i) - p.z
    dx*dx + dy*dy + dz*dz
  }

  def findClosestIndex (p: Cartesian3): Int = {
    var l = 1
    var r = length-2
    var i = r/2
    var dl = dist2(i,p) - dist2(i-1,p)
    var dr = dist2(i+1,p) - dist2(i,p)
    var di = 0.0

    while (signum(dl) == signum(dr)) {
      if (dr < 0) {  // bisect right
        l = i
      } else {  // bisect left
        r = i
      }
      i = (l + r)/2
      di = dist2(i,p)
      dl = di - dist2(i-1,p)
      dr = dist2(i+1,p) - di
    }

    i
  }

  def findClosestPoint (p: Cartesian3): Cartesian3 = {
    val i = findClosestIndex(p)

    val pl = this(i-1)
    val pm = this(i)
    val pr = this(i+1)
    val pLeft = Cartesian3.closestPointInLineSegment( pl, pm, p)
    val pRight = Cartesian3.closestPointInLineSegment( pm, pr, p)

    val dLeft2 = (p - pLeft).length2
    val dRight2 = (p - pRight).length2

    println(s"p:      ${Datum.ecefToWGS84(p)}")
    println(s"pl:     ${Datum.ecefToWGS84(pl)}  : ${(p-pl).length}")
    println(s"pLeft:  ${Datum.ecefToWGS84(pLeft)}  : ${sqrt(dLeft2)}")
    println(s"pm:     ${Datum.ecefToWGS84(pm)}  : ${(p-pm).length}")
    println(s"pRight: ${Datum.ecefToWGS84(pRight)} : ${sqrt(dRight2)}")
    println(s"pr:     ${Datum.ecefToWGS84(pr)}  : ${(p-pr).length}")

    if (dLeft2 < dRight2) pLeft else pRight
  }

  def findClosestGroundTrackPoint (p: Cartesian3): Cartesian3 = {
    val i = findClosestIndex(p)
    val gp = MutXyz()
    gp.setToIntersectionWithPlane( this(i-1), this(i+1), p)
    Datum.scaleToEarthRadius( gp)
    gp
  }

  // this is just a coarse approximation - we don't want to iterate over all points
  def getAverageAltitude: Length = {
    val xyz = new MutXyzPos()
    xyz.setFromMeters(x(0), y(0), z(0))
    val p0 = Datum.ecefToWGS84(xyz)

    val l = length-1
    xyz.setFromMeters(x(l), y(l), z(l))
    val p1 =  Datum.ecefToWGS84(xyz)

    (p1.altitude + p0.altitude) / 2
  }
}
