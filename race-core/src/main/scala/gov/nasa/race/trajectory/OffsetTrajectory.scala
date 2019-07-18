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
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.{Angle, AngleArray, DateTime, DateArray, DeltaAngleArray, DeltaDateArray, DeltaLengthArray, Length, LengthArray, Time}


object OffsetTrajectory {
  /** geographic center of continental US */
  val USOffset = LatLonPos( Degrees(39.833333), Degrees(-98.583333), Length0)
}

/**
  * common storage abstraction for trajectories that use a offsets to store time and position as 32bit quantities
  *
  * maximum duration is <24d
  * for locations within the continental US the positional error due to truncation is <1m, which
  * is below the accuracy of single frequency GPS (~2m URE according to FAA data)
  *
  * all index values are direct array indices
  */
trait OffsetTraj extends ArrayTraj[OffsetTraj] {

  val offset: LatLonPos

  // those have to be vars to support growable trajectories
  protected[trajectory] var ts: DeltaDateArray = new DeltaDateArray(capacity)
  protected[trajectory] var lats: DeltaAngleArray = new DeltaAngleArray(capacity,offset.lat)
  protected[trajectory] var lons: DeltaAngleArray = new DeltaAngleArray(capacity,offset.lon)
  protected[trajectory] var alts: DeltaLengthArray = new DeltaLengthArray(capacity,offset.altitude)

  protected[trajectory] def copyArraysFrom (other: OffsetTraj, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
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

  override def emptyMutable (initCapacity: Int): MutTrajectory = new MutOffsetTrajectory(initCapacity,offset)
}


/**
  * invariant representation of offset based trajectory
  */
class OffsetTrajectory (val capacity: Int, val offset: LatLonPos) extends Traj with OffsetTraj {
  override def size = capacity

  override def snapshot: Trajectory = this

  override def traceSnapshot (dur: Time): Trajectory = snap(getDurationStartIndex(dur), size-1, new OffsetTrajectory(_,offset))

  override def branch: MutTrajectory = yieldInitialized(new MutOffsetTrajectory(capacity*2, offset)) { o=>
    o._size = capacity
    o.copyArraysFrom(this, 0, 0, capacity)
  }
}

/**
  * a growable offset trajectory
  */
class MutOffsetTrajectory (protected var _capacity: Int, val offset: LatLonPos)
                                   extends MutTraj with OffsetTraj {

  override def clone: MutOffsetTrajectory = yieldInitialized(new MutOffsetTrajectory(capacity,offset)) { o=>
    o._size = _size
    o.copyArraysFrom(this, 0, 0, _size)
  }

  override def snapshot: Trajectory = snap(0,_size-1, new OffsetTrajectory(_,offset))

  override def traceSnapshot (dur: Time): Trajectory = snap(getDurationStartIndex(dur), _size-1, new OffsetTrajectory(_,offset))

  override def branch: MutTrajectory = clone
}

class MutUSTrajectory (capacity: Int) extends MutOffsetTrajectory(capacity,OffsetTrajectory.USOffset)

class USTrajectory (capacity: Int) extends OffsetTrajectory(capacity,OffsetTrajectory.USOffset)


/**
  * a mutable offset trajectory that stores N last track points in a circular buffers
  */
class OffsetTrace(val capacity: Int, val offset: LatLonPos) extends OffsetTraj with TraceArrayTraj[OffsetTraj] {

  protected val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def clone: OffsetTrace = yieldInitialized(new OffsetTrace(capacity,offset)) { o=>
    o.setIndices(head,tail)
    o.copyArraysFrom(this, 0, 0, capacity)
  }

  override def snapshot: Trajectory = snap(tail,head, new OffsetTrajectory(_,offset))

  override def traceSnapshot (dur: Time): Trajectory = snap(getDurationStartIndex(dur), head, new OffsetTrajectory(_,offset))

  override def branch: MutTrajectory = clone
}

class USTrace(capacity: Int) extends OffsetTrace(capacity,OffsetTrajectory.USOffset)
