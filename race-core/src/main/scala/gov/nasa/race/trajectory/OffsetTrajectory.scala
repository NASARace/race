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
import gov.nasa.race.uom.{Angle, AngleArray, Length, LengthArray, Time, TimeArray}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Time._
import org.joda.time.DateTime


object OffsetTrajectory {
  /** geographic center of continental US */
  val USOffset = LatLonPos( Degrees(39.833333), Degrees(-98.583333), Length0)
}

/**
  * common abstraction for trajectories that use a offsets to store time and position as 32bit quantities
  *
  * maximum duration is <900h
  * for locations within the continental US the positional error due to truncation is <1m, which
  * is below the accuracy of single frequency GPS (~2m URE according to FAA data)
  *
  * all index values are direct array indices
  */
trait OffsetTraj extends Trajectory {
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

  override def getTime(i: Int): Long = ts(i) + t0Millis

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

  override def getDataPoint (i: Int, p: TDP3): TDP3 = {
    p.set(ts(i) + t0Millis,
          (Degrees(lats(i)) + offset.φ).toDegrees,
          (Degrees(lons(i)) + offset.λ).toDegrees,
          (Meters(alts(i)) + offset.altitude).toMeters)
  }

  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: TimeArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    t(destIdx) = Milliseconds(ts(i) + t0Millis)
    lat(destIdx) = Degrees(lats(i)) + offset.φ
    lon(destIdx) = Degrees(lons(i)) + offset.λ
    alt(destIdx) = Meters(alts(i)) + offset.altitude
  }
}


/**
  * a growable offset trajectory
  */
class MutOffsetTrajectory (initCapacity: Int, val offset: LatLonPos) extends OffsetTraj with MutTrajectory {
  protected var _capacity = initCapacity
  protected[trajectory] var _size: Int = 0

  protected def ensureSize (n: Int): Unit = {
    if (n > capacity) {
      var newCapacity = _capacity * 2
      while (newCapacity < n) newCapacity *= 2
      if (newCapacity > Int.MaxValue) newCapacity = Int.MaxValue // should probably be an exception

      val newTs = new Array[Int](newCapacity)
      val newLats = new Array[Float](newCapacity)
      val newLons = new Array[Float](newCapacity)
      val newAlts = new Array[Float](newCapacity)

      System.arraycopy(ts,0,newTs,0,_capacity)
      System.arraycopy(lats,0,lats,0,_capacity)
      System.arraycopy(lons,0,lons,0,_capacity)
      System.arraycopy(alts,0,alts,0,_capacity)

      ts = newTs
      lats = newLats
      lons = newLons
      alts = newAlts
      _capacity = newCapacity
    }
  }

  override def clone: MutOffsetTrajectory = {
    val o = new MutOffsetTrajectory(capacity,offset)
    o.t0Millis = t0Millis
    o._size = _size
    o.copyArraysFrom(this, 0, 0, _size)
    o
  }

  def size = _size
  def capacity = _capacity

  override def append(millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    ensureSize(_size + 1)
    update( _size, millis,lat,lon,alt)
    _size += 1
  }

  override def clear: Unit = {
    _size = 0
  }

  override def drop(n: Int): Unit = {
    if (_size > n) {
      val i = n-1
      _size -= n
      System.arraycopy(ts,i,ts,0,_size)
      System.arraycopy(lats,i,lats,0,_size)
      System.arraycopy(lons,i,lons,0,_size)
      System.arraycopy(alts,i,alts,0,_size)
    } else {
      _size = 0
    }
  }

  override def dropRight(n: Int): Unit = {
    if (_size > n) {
      _size -= n
    } else {
      _size = 0
    }  }

  override def apply(i: Int): TrackPoint = {
    if (i < 0 || i >= _size) throw new IndexOutOfBoundsException(i)
    getTrackPoint(i)
  }

  override def iterator: Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0,_size)(getTrackPoint)
  }

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0,_size)(updateMutTrackPoint(p))
  }

  override def reverseIterator: Iterator[TrackPoint] = {
    new CountDownIterator[TrackPoint](_size-1,_size)(getTrackPoint)
  }

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    new CountDownIterator[TrackPoint](_size-1,_size)(updateMutTrackPoint(p))
  }

  override def copyToArrays(t: TimeArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    var i = 0
    var j = 0
    while (i < _size){
      updateUOMArrayElements(i, j, t, lat, lon, alt)
      i += 1
      j += 1
    }
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
class OffsetTrajectory (val capacity: Int, val offset: LatLonPos) extends OffsetTraj {
  def size = capacity

  override def apply(i: Int): TrackPoint = getTrackPoint(i)

  override def iterator: Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0,capacity)(getTrackPoint)
  }

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0,capacity)(updateMutTrackPoint(p))
  }

  override def reverseIterator: Iterator[TrackPoint] = {
    new CountDownIterator[TrackPoint](capacity-1,capacity)(getTrackPoint)
  }

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    new CountDownIterator[TrackPoint](capacity-1,capacity)(updateMutTrackPoint(p))
  }

  override def copyToArrays(t: TimeArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    var i = 0
    var j = 0
    while (i < capacity){
      updateUOMArrayElements(i, j, t, lat, lon, alt)
      i += 1
      j += 1
    }
  }

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
class OffsetTrajectoryTrace(val capacity: Int, val offset: LatLonPos) extends OffsetTraj with MutTrajectory with CircularSeq {

  protected val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def clone: OffsetTrajectoryTrace = {
    val o = new OffsetTrajectoryTrace(capacity,offset)
    o.copyArraysFrom(this, 0, 0, capacity)
    o
  }

  override def getTime(off: Int): Long = {
    if (off >= _size || off < 0) throw new IndexOutOfBoundsException(off)
    super.getTime(storeIdx(off))
  }

  override def append (millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    update( incIndices, millis,lat,lon,alt)
  }

  override def apply (off: Int): TrackPoint = {
    if (off >= _size || off < 0) throw new IndexOutOfBoundsException(off)
    getTrackPoint(storeIdx(off))
  }

  override def getDataPoint (off: Int, p: TDP3): TDP3 = {
    if (off >= _size || off < 0) throw new IndexOutOfBoundsException(off)
    super.getDataPoint(storeIdx(off),p)
  }

  override def iterator: Iterator[TrackPoint] = new ForwardIterator(getTrackPoint)

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = new ForwardIterator(updateMutTrackPoint(p))

  override def reverseIterator: Iterator[TrackPoint] = new ReverseIterator(getTrackPoint)

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = new ReverseIterator(updateMutTrackPoint(p))

  override def copyToArrays(t: TimeArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    var j = 0
    var i = tail
    while (i < head){
      updateUOMArrayElements(i % capacity, j, t, lat, lon, alt)
      j += 1
      i += 1
    }
  }

  override def snapshot: Trajectory = {
    val o = new OffsetTrajectory(_size,offset)
    o.t0Millis = t0Millis

    val i0 = tail % capacity
    if (i0 + _size < capacity) {
      o.copyArraysFrom(this, i0, 0, _size)
    } else {
      val len = capacity - i0
      o.copyArraysFrom(this, i0, 0, len)
      o.copyArraysFrom(this, 0, len, _size - len)
    }
    o
  }

  override def branch: MutTrajectory = clone
}

class USTrajectoryTrace (capacity: Int) extends OffsetTrajectoryTrace(capacity,OffsetTrajectory.USOffset)
