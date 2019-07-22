/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import gov.nasa.race._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Angle, AngleArray, DateTime, DateArray, Length, LengthArray, Time}

/**
  * trajectory storage that does not try to save memory and stores data in full 64 bit quantities
  */
trait AccurateTraj extends ArrayTraj[AccurateTraj] {

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

  def getDateMillis(i: Int): Long = ts(i).toEpochMillis

  protected def update(i: Int, date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit = {
    ts(i) = date
    lats(i) = lat
    lons(i) = lon
    alts(i) = alt
  }

  protected def getTrackPoint (i: Int): TrackPoint = {
    val pos = new LatLonPos(lats(i), lons(i), alts(i))
    new TrajectoryPoint(ts(i), pos)
  }

  protected def updateMutTrackPoint (p: MutTrajectoryPoint) (i: Int): TrackPoint = {
    p.update(ts(i), lats(i), lons(i), alts(i))
  }

  override def getTDP3 (i: Int, p: TDP3): TDP3 = {
    p.set(ts(i).toEpochMillis, lats(i).toDegrees, lons(i).toDegrees, alts(i).toMeters)
  }

  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    t(destIdx) = ts(i)
    lat(destIdx) = lats(i)
    lon(destIdx) = lons(i)
    alt(destIdx) = alts(i)
  }

  override def emptyMutable (initCapacity: Int): MutTrajectory = new MutAccurateTrajectory(initCapacity)
}

/**
  * fixed size accurate trajectory
  */
class AccurateTrajectory (val capacity: Int) extends Traj with AccurateTraj {
  override def size = capacity

  override def snapshot: Trajectory = this

  override def traceSnapshot (dur: Time): Trajectory = snap(getDurationStartIndex(dur), size-1, new AccurateTrajectory(_))

  override def branch: MutTrajectory = yieldInitialized(new MutAccurateTrajectory(capacity)) { o=>
    o._size = capacity
    o.copyArraysFrom(this, 0, 0, size)
  }
}

/**
  * mutable (growable) accurate trajectory
  */
class MutAccurateTrajectory (protected var _capacity: Int) extends MutTraj with AccurateTraj {

  override def clone: MutAccurateTrajectory = yieldInitialized(new MutAccurateTrajectory(_capacity)) { o =>
    o._size = _size
    o.copyArraysFrom(this,0,0,_size)
  }

  override def snapshot: Trajectory = snap(0,_size-1, new AccurateTrajectory(_))

  override def traceSnapshot (dur: Time): Trajectory = snap(getDurationStartIndex(dur), _size-1, new AccurateTrajectory(_))

  override def branch: MutTrajectory = clone
}

/**
  * accurate trace trajectory that stores N last track points
  */
class AccurateTrace(val capacity: Int) extends AccurateTraj with TraceArrayTraj[AccurateTraj] {
  protected val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def clone: AccurateTrace = yieldInitialized(new AccurateTrace(capacity)) { o=>
    o._size = _size
    o.setIndices(head,tail)
    o.copyArraysFrom(this,0,0,_size)
  }

  override def snapshot: Trajectory = snap(tail,head, new AccurateTrajectory(_))

  override def traceSnapshot (dur: Time): Trajectory = snap(getDurationStartIndex(dur), head, new AccurateTrajectory(_))

  override def branch: MutTrajectory = clone
}