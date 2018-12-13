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
package gov.nasa.race.track

/**
  * a compressed trajectory trace that supports spline interpolation
  */
class CompressedSpTrajectoryTrace (val capacity: Int) extends TrajectoryTrace with CompressedTrajectory {

  override protected var data: Array[Long] = new Array(capacity * 2)

  //--- forward computed intermediate spline params (used in backwards spline coefficient calc)
  protected val m: Array[Float] = new Array(capacity)  // only depends on t,tPrev
  protected val sLat: Array[Float] = new Array(capacity)
  protected val sLon: Array[Float] = new Array(capacity)
  protected val sAlt: Array[Float] = new Array(capacity)

  //--- last value caches (saves us from storing them in arrays
  protected var tPrev: Long      = 0  // last observation time
  protected var tPrev1: Long     = 0  // second last observation time
  protected var latPrev: Double  = 0
  protected var lonPrev: Double  = 0
  protected var altPrev: Double  = 0

  protected var hPrev: Int       = 0
  protected var mPrev: Float     = 0
  protected var bLatPrev: Float  = 0
  protected var bLonPrev: Float  = 0
  protected var bAltPrev: Float  = 0
  protected var sLatPrev: Float  = 0
  protected var sLonPrev: Float  = 0
  protected var sAltPrev: Float  = 0



  override def addPre(t: Long, lat: Double, lon: Double, alt: Double): Trajectory = {
    val i = head // coefficient index to update (previous observation

    super.addPre(t,lat,lon,alt)

    // note we don't have to do anything for the first entry since it would only have to set m(0), sX(0) to zero
    if (_size > 1){
      val h = (t - tPrev).toInt
      val l = 2f*(t - tPrev1) - hPrev * mPrev

      val mi = h / l
      m(i) = mi

      val bLat = 3f*(lat - latPrev).toFloat / h
      val bLon = 3f*(lon - lonPrev).toFloat / h
      val bAlt = 3f*(alt - altPrev).toFloat / h

      val sLati = ((bLat - bLatPrev) - hPrev * sLatPrev) / l
      val sLoni = ((bLon - bLonPrev) - hPrev * sLonPrev) / l
      val sAlti = ((bAlt - bAltPrev) - hPrev * sAltPrev) / l
      sLat(i) = sLati
      sLon(i) = sLoni
      sAlt(i) = sAlti

      hPrev = h
      mPrev = mi
      bLatPrev = bLat
      bLonPrev = bLon
      bAltPrev = bAlt
      sLatPrev = sLati
      sLonPrev = sLoni
      sAltPrev = sAlti
    }

    tPrev1 = tPrev
    tPrev = t
    latPrev = lat
    lonPrev = lon
    altPrev = alt

    this
  }
}