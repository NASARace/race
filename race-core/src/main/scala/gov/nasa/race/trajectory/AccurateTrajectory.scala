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

import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Angle, AngleArray, DateArray, Length, LengthArray}

/**
  * trajectory storage that does not try to save memory and stores data in full 64 bit quantities
  */
trait AccurateTraj extends Traj {

  protected[trajectory] var ts: DateArray = new DateArray(capacity)
  protected[trajectory] var lats: AngleArray = new AngleArray(capacity)
  protected[trajectory] var lons: AngleArray = new AngleArray(capacity)
  protected[trajectory] var alts: LengthArray = new LengthArray(capacity)

  protected[trajectory] def copyArraysFrom (other: AccurateTraj, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    ts.copyFrom(other.ts, srcIdx,dstIdx,len)
    lats.copyFrom(other.lats,srcIdx,dstIdx,len)
    lons.copyFrom(other.lons, srcIdx,dstIdx,len)
    alts.copyFrom(other.alts,srcIdx,dstIdx,len)
  }

  protected def grow (newCapacity: Int): Unit = {
    ts = ts.grow(newCapacity)
    lats = lats.grow(newCapacity)
    lons = lons.grow(newCapacity)
    alts = alts.grow(newCapacity)
  }

  def getDateMillis(i: Int): Long = ts(i).toMillis

  protected def update(i: Int, millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    lats(i) = lat
    lons(i) = lon
    alts(i) = alt
  }

  protected def getTrackPoint (i: Int): TrackPoint = {
    val pos = new LatLonPos(lats(i), lons(i), alts(i))
    new TrajectoryPoint(ts(i).toDateTime, pos)
  }

  protected def updateMutTrackPoint (p: MutTrajectoryPoint) (i: Int): TrackPoint = {
    p.update(ts(i).toMillis, lats(i), lons(i), alts(i))
  }

  override def getTDP3 (i: Int, p: TDP3): TDP3 = {
    p.set(ts(i).toMillis, lats(i).toDegrees, lons(i).toDegrees, alts(i).toMeters)
  }

  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    t(destIdx) = ts(i)
    lat(destIdx) = lats(i)
    lon(destIdx) = lons(i)
    alt(destIdx) = alts(i)
  }
}

/**
  * fixed size accurate trajectory
  */
class AccurateTrajectory (val capacity: Int) extends Traj with AccurateTraj {
  override def size = capacity

  override def snapshot: Trajectory = this

  override def branch: MutTrajectory = {
    val o = new MutAccurateTrajectory(capacity)
    o.copyArraysFrom(this, 0, 0, size)
    o._size = capacity
    o
  }
}

/**
  * mutable (growable) accurate trajectory
  */
class MutAccurateTrajectory (initCapacity: Int) extends MutTraj with AccurateTraj {
  protected var _capacity = initCapacity

  override def clone: MutAccurateTrajectory = {
    val o = new MutAccurateTrajectory(_capacity)
    o._size = _size
    o.copyArraysFrom(this,0,0,_size)
    o
  }

  override def snapshot: Trajectory = this

  override def branch: MutTrajectory = clone
}

/**
  * accurate trace trajectory that stores N last track points
  */
class AccurateTraceTrajectory(val capacity: Int) extends TraceTraj with AccurateTraj {
  protected val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def clone: AccurateTraceTrajectory = {
    val o = new AccurateTraceTrajectory(capacity)
    o._size = _size
    o.copyArraysFrom(this,0,0,_size)
    o.setIndices(head,tail)
    o
  }

  override def snapshot: Trajectory = {
    val o = new AccurateTrajectory(_size)
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