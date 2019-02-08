/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.trajectory

import gov.nasa.race.geo.{LatLon, LatLonArray, LatLonPos}
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.Date._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.{Angle, AngleArray, DateArray, DeltaDateArray, DeltaLengthArray, Length, LengthArray}

/**
  * common storage abstraction of compressed trajectories that store data in 32bit quantities.
  *
  * Time values are stored as offsets to the initial TrackPoint, which limits durations to <24d
  * Altitude is stored as Float meters (~7 digits give cm accuracy for flight altitudes < 99,999m)
  * lat/lon encoding uses the algorithm from (see http://www.dupuis.me/node/35), which has ~10cm accuracy
  *
  * all indices are 0-based array offsets
  */
trait CompressedTraj extends Traj {

  protected[trajectory] var ts: DeltaDateArray = new DeltaDateArray(capacity)
  protected[trajectory] var latLons: LatLonArray = new LatLonArray(capacity)
  protected[trajectory] var alts: DeltaLengthArray = new DeltaLengthArray(capacity,Length0)

  protected def grow(newCapacity: Int): Unit = {
    ts = ts.grow(newCapacity)
    latLons = latLons.grow(newCapacity)
    alts = alts.grow(newCapacity)
  }

  protected[trajectory] def copyArraysFrom (other: CompressedTraj, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    ts.copyFrom(other.ts, srcIdx, dstIdx, len)
    latLons.copyFrom(other.latLons, srcIdx, dstIdx, len)
    alts.copyFrom(alts, srcIdx, dstIdx, len)
  }

  def getDateMillis(i: Int): Long = ts(i).toMillis

  protected def update(i: Int, millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    ts(i) = EpochMillis(millis)
    latLons(i) = LatLon(lat, lon)
    alts(i) = alt
  }

  protected def getTrackPoint(i: Int): TrackPoint = {
    val p = latLons(i)
    val pos = new LatLonPos(p.lat, p.lon, alts(i))
    new TrajectoryPoint(ts(i).toDateTime, pos)
  }

  protected def updateMutTrackPoint(mp: MutTrajectoryPoint)(i: Int): TrackPoint = {
    val p = latLons(i)
    mp.update(ts(i).toMillis, p.lat, p.lon, alts(i))
  }

  override def getTDP3 (i: Int, tdp: TDP3): TDP3 = {
    val p = latLons(i)
    tdp.set(ts(i).toMillis, p.lat.toDegrees, p.lon.toDegrees, alts(i).toMeters)
  }

  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    val p = latLons(i)

    t(destIdx) = ts(i)
    lat(destIdx) = p.lat
    lon(destIdx) = p.lon
    alt(destIdx) = alts(i)
  }
}

class CompressedTrajectory (val capacity: Int) extends Traj with CompressedTraj {
  override def size = capacity

  override def snapshot: Trajectory = this

  override def branch: MutTrajectory = {
    val o = new MutCompressedTrajectory(capacity)
    o.copyArraysFrom(this, 0, 0, size)
    o._size = capacity
    o
  }
}

class MutCompressedTrajectory (initCapacity: Int) extends MutTraj with CompressedTraj {
  protected var _capacity = initCapacity

  override def clone: MutCompressedTrajectory = {
    val o = new MutCompressedTrajectory(_capacity)
    o._size = _size
    o.copyArraysFrom(this,0,0,_size)
    o
  }

  override def snapshot: Trajectory = this

  override def branch: MutTrajectory = clone
}

class CompressedTraceTrajectory(val capacity: Int) extends TraceTraj with CompressedTraj {
  protected val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def clone: CompressedTraceTrajectory = {
    val o = new CompressedTraceTrajectory(capacity)
    o._size = _size
    o.copyArraysFrom(this,0,0,_size)
    o.setIndices(head,tail)
    o
  }

  override def snapshot: Trajectory = {
    val o = new CompressedTrajectory(_size)
    if (head >= tail) {
      o.copyArraysFrom(this,tail,0,_size)
    } else {
      o.copyArraysFrom(this,tail ,0, capacity - tail)
      o.copyArraysFrom(this,0, capacity - tail, head+1)
    }
    o
  }

  override def branch: MutTrajectory = clone
}
