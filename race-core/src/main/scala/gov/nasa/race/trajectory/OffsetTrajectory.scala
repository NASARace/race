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

import gov.nasa.race.common.{CircularSeq, CountDownIterator, CountUpIterator}
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Angle, AngleArray, DateArray, Length, LengthArray, Time, TimeArray}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Date._
import gov.nasa.race.uom.Time._
import gov.nasa.race.util.ArrayUtils
import org.joda.time.DateTime


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
trait OffsetTraj extends Traj {
  protected[trajectory] var t0Millis: Long = -1 // start time of trajectory in epoch millis (set when entering first point)

  val offset: LatLonPos

  // those have to be vars to support growable trajectories
  protected[trajectory] var ts: Array[Int] = new Array(capacity)
  protected[trajectory] var lats: Array[Float] = new Array(capacity)
  protected[trajectory] var lons: Array[Float] = new Array(capacity)
  protected[trajectory] var alts: Array[Float] = new Array(capacity)

  protected[trajectory] def copyArraysFrom (other: OffsetTraj, srcIdx: Int, dstIdx: Int, len: Int): Unit = {
    System.arraycopy(other.ts, srcIdx, ts, dstIdx, len)
    System.arraycopy(other.lats, srcIdx, lats, dstIdx, len)
    System.arraycopy(other.lons, srcIdx, lons, dstIdx, len)
    System.arraycopy(other.alts, srcIdx, alts, dstIdx, len)
  }

  protected def grow (newCapacity: Int): Unit = {
    ts = ArrayUtils.grow(ts,newCapacity)
    lats = ArrayUtils.grow(lats,newCapacity)
    lons = ArrayUtils.grow(lons,newCapacity)
    alts = ArrayUtils.grow(alts,newCapacity)
  }

  def getDateMillis(i: Int): Long = ts(i) + t0Millis

  protected def update(i: Int, millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    if (t0Millis < 0) {
      t0Millis = millis
      ts(i) = 0
    } else {
      ts(i) = (millis - t0Millis).toInt
    }
    lats(i) = (lat - offset.φ).toDegrees.toFloat
    lons(i) = (lon - offset.λ).toDegrees.toFloat
    alts(i) = (alt - offset.altitude).toMeters.toFloat
  }

  protected def getTrackPoint (i: Int): TrackPoint = {
    val pos = new LatLonPos(Degrees(lats(i)) + offset.φ, Degrees(lons(i)) + offset.λ, Meters(alts(i)) + offset.altitude)
    new TrajectoryPoint(new DateTime(ts(i).toLong + t0Millis), pos)
  }

  protected def updateMutTrackPoint (p: MutTrajectoryPoint) (i: Int): TrackPoint = {
    p.update(ts(i).toLong + t0Millis, Degrees(lats(i)) + offset.φ, Degrees(lons(i)) + offset.λ, Meters(alts(i)) + offset.altitude)
  }

  override def getTDP3 (i: Int, p: TDP3): TDP3 = {
    p.set(ts(i) + t0Millis,
          (Degrees(lats(i)) + offset.φ).toDegrees,
          (Degrees(lons(i)) + offset.λ).toDegrees,
          (Meters(alts(i)) + offset.altitude).toMeters)
  }

  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    t(destIdx) = EpochMillis(t0Millis + ts(i))
    lat(destIdx) = Degrees(lats(i)) + offset.φ
    lon(destIdx) = Degrees(lons(i)) + offset.λ
    alt(destIdx) = Meters(alts(i)) + offset.altitude
  }
}

/**
  * a growable offset trajectory
  */
class MutOffsetTrajectory (initCapacity: Int, val offset: LatLonPos)
                                   extends MutTraj with OffsetTraj {
  protected var _capacity = initCapacity

  override def clone: MutOffsetTrajectory = {
    val o = new MutOffsetTrajectory(capacity,offset)
    o.t0Millis = t0Millis
    o._size = _size
    o.copyArraysFrom(this, 0, 0, _size)
    o
  }

  override def snapshot: Trajectory = {
    val o = new OffsetTrajectory(_size,offset)
    o.t0Millis = t0Millis
    o.copyArraysFrom(this, 0, 0, _size)
    o
  }

  override def branch: MutTrajectory = clone
}

class MutUSTrajectory (capacity: Int) extends MutOffsetTrajectory(capacity,OffsetTrajectory.USOffset)

/**
  * invariant representation of offset based trajectory
  */
class OffsetTrajectory (val capacity: Int, val offset: LatLonPos) extends Traj with OffsetTraj {
  override def size = capacity

  override def snapshot: Trajectory = this // no need to create a new one, we are invariant

  override def branch: MutTrajectory = {
    val o = new MutOffsetTrajectory(capacity*2, offset) // no point branching if we don't append subsequently
    o.t0Millis = t0Millis
    o._size = capacity
    o.copyArraysFrom(this, 0, 0, capacity)
    o
  }
}

class USTrajectory (capacity: Int) extends OffsetTrajectory(capacity,OffsetTrajectory.USOffset)


/**
  * a mutable offset trajectory that stores N last track points in a circular buffers
  */
class OffsetTrajectoryTrace(val capacity: Int, val offset: LatLonPos) extends TraceTraj with OffsetTraj {

  protected val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def clone: OffsetTrajectoryTrace = {
    val o = new OffsetTrajectoryTrace(capacity,offset)
    o.copyArraysFrom(this, 0, 0, capacity)
    o.setIndices(head,tail)
    o
  }

  override def snapshot: Trajectory = {
    val o = new OffsetTrajectory(_size,offset)
    o.t0Millis = t0Millis

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

class USTrajectoryTrace (capacity: Int) extends OffsetTrajectoryTrace(capacity,OffsetTrajectory.USOffset)
