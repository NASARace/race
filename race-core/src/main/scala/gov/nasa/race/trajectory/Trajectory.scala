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

import gov.nasa.race.common.Nat.N3
import gov.nasa.race.common.{CircularSeq, CountDownIterator, CountUpIterator, TDataPoint3, TDataSource, TInterpolant}
import gov.nasa.race.geo.{GeoPosition, LatLonPos, MutLatLonPos}
import gov.nasa.race.track.{TrackPoint, Velocity2D}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Time._
import gov.nasa.race.uom.{Angle, AngleArray, DateArray, DateTime, Length, LengthArray, Speed, Time}

object Trajectory {

  // mostly for generating tests
  def apply (dps: (Long,Double,Double,Double)*): Trajectory = {
    create(new MutAccurateTrajectory(_),dps:_*)
  }

  // mostly for generating tests
  def create (f: Int=>MutTrajectory, dps: (Long,Double,Double,Double)*): Trajectory = {
    val tr = f(dps.size)
    val dp = new TDP3
    dps.foreach { p=>
      dp.set(p._1,p._2,p._3,p._4)
      tr += dp
    }
    tr
  }
}

/**
  * immutable object holding trajectory point information
  */
class TrajectoryPoint (val date: DateTime, val position: GeoPosition) extends TrackPoint {
  def this (t: DateTime, lat: Angle, lon: Angle, alt: Length) = this(t, LatLonPos(lat,lon,alt))

  def setTDataPoint3(p: TDataPoint3): Unit = {
    p.set(date.toEpochMillis, position.latDeg, position.lonDeg, position.altMeters)
  }

  override def toString(): String = s"TrajectoryPoint{ date: $date, pos: $position}"
}

/**
  * mutable object holding trajectory point information
  * can be used to sequentially iterate through trajectory without allocation
  */
class MutTrajectoryPoint (var date: DateTime, val position: MutLatLonPos) extends TrackPoint {
  def this() = this(DateTime.UndefinedDateTime, new MutLatLonPos(Angle0,Angle0,Length0))

  def update (d: DateTime, lat: Angle, lon: Angle, alt: Length): this.type = {
    date = d
    position.update(lat,lon,alt)
    this
  }

  def updateTDataPoint3(p: TDataPoint3): Unit = {
    p.set(date.toEpochMillis, position.latDeg, position.lonDeg, position.altMeters)
  }

  def toTrajectoryPoint: TrajectoryPoint = new TrajectoryPoint(date, position.toLatLonPos)
}

/**
  * the low level type version of a TrajectoryPoint which is compatible with TInterpolant/TDataSource
  * but adds uom accessors and high level conversion
  *
  * Note that we store lat/lon in degrees
  */
class TDP3 (_millis: Long, _lat: Double, _lon: Double, _alt: Double)
                                            extends TDataPoint3(_millis,_lat,_lon,_alt) with GeoPosition {
  def this() = this(0,0.0,0.0,0.0)

  def epochMillis: DateTime = DateTime.ofEpochMillis(millis)

  override def φ: Angle = Degrees(_0)
  def φ_= (lat: Angle): Unit = _0 = lat.toDegrees

  override def λ: Angle = Degrees(_1)
  def λ_= (lon: Angle): Unit = _1 = lon.toDegrees

  override def altitude: Length = Meters(_2)
  def altitude_= (alt: Length): Unit = _2 = alt.toMeters

  override def clone: TDP3 = new TDP3(millis,_0,_1,_2)

  def toTrajectoryPoint = new TrajectoryPoint(DateTime.ofEpochMillis(millis), LatLonPos(Degrees(_0),Degrees(_1),Meters(_2)))

  def updateTrajectoryPoint (p: MutTrajectoryPoint): Unit = {
    p.update(epochMillis, Degrees(_0), Degrees(_1), Meters(_2))
  }

  def toTDPString: String = super[TDataPoint3].toString
}

/**
  * public root interface for trajectories
  *
  * note that implementation does not imply mutability, storage (direct, compressed, local)
  * or coverage (full, trace). We only use an abstract TrackPoint result type here
  */
trait Trajectory extends TDataSource[N3,TDP3] {

  def capacity: Int  // max size (might be dynamically adapted if mutable)
  def size: Int

  @inline def isEmpty: Boolean = size == 0
  @inline def nonEmpty: Boolean = size > 0

  def getFirstDate: DateTime
  def getLastDate: DateTime
  @inline def getDuration: Time = getLastDate.timeSince(getFirstDate)
  @inline def includesDate (d: DateTime): Boolean = d >= getFirstDate && d <= getLastDate

  /**
    * get (logical) data point index i for given time with tdp(i).epochMillis <= d
    * return -1 if time is lower than first data point, or size-1 if greater than
    * last data point
    */
  def indexOf(d: DateTime): Int = {
    if (d < getFirstDate) return -1
    else if (d >= getLastDate) return size-1
    else {
      var i = 0
      foreach (new TDP3){ p=>
        if (p.epochMillis > d) return i
        i += 1
      }
      throw new RuntimeException("should not get here")
    }
  }

  def getAverageUpdateDuration: Time = {
    if (size < 2) Time.UndefinedTime else getLastDate.timeSince(getFirstDate) / size
  }

  def getAverageAltitude: Length = {
    val tdp3 = new TDP3
    var n=1
    var m=0.0
    foreach (tdp3) { p=>
      m += (p.altMeters - m) / n
      n += 1
    }
    Meters(m)
  }

  def newDataPoint: TDP3 = new TDP3(0,0,0,0)  // TDataSource interface

  /**
    * trajectory point access with logical index [0..size-1]
    * throws ArrayIndexOutOfBounds exception if outside limits
    */
  def apply (i: Int): TrackPoint
  def dataPoint (i: Int): TDP3

  def getFirst: Option[TrackPoint] = if (isEmpty) None else Some(apply(0))
  def getLast: Option[TrackPoint] = if (isEmpty) None else Some(apply(size-1))

  def iterator: Iterator[TrackPoint]
  def iterator (p: MutTrajectoryPoint): Iterator[TrackPoint]
  def iterator (p: TDP3): Iterator[TDP3]

  def foreach (f: (TrackPoint)=>Unit): Unit = {
    val it = iterator
    while (it.hasNext) f(it.next())
  }
  def foreach (p: MutTrajectoryPoint)(f: (TrackPoint)=>Unit): Unit = {
    val it = iterator(p)
    while (it.hasNext) f(it.next())
  }
  def foreach (p: TDP3)(f: (TDP3)=>Unit): Unit = {
    val it = iterator(p)
    while (it.hasNext) f(it.next())
  }

  def reverseIterator: Iterator[TrackPoint]
  def reverseIterator (p: MutTrajectoryPoint): Iterator[TrackPoint]
  def reverseIterator (p: TDP3): Iterator[TDP3]

  def foreachReverse (f: (TrackPoint)=>Unit): Unit = {
    val it = reverseIterator
    while (it.hasNext) f(it.next())
  }
  def foreachReverse (p: MutTrajectoryPoint)(f: (TrackPoint)=>Unit): Unit = {
    val it = reverseIterator(p)
    while (it.hasNext) f(it.next())
  }
  def foreachReverse (p: TDP3)(f: (TDP3)=>Unit): Unit = {
    val it = reverseIterator(p)
    while (it.hasNext) f(it.next())
  }

  def find (pred: TrackPoint=>Boolean): Option[TrackPoint] = {
    val tp = new MutTrajectoryPoint()
    val it = iterator(tp)
    while (it.hasNext) {
      if (pred(it.next())) return Some(tp.toTrajectoryPoint)
    }
    None
  }

  def copyToArrays (t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit

  /**
    * return a new Trajectory object that preserves the current state (immutable)
    */
  def snapshot: Trajectory

  /**
    * returns an immutable trajectory that covers all most recent datapoints within specified duration
    */
  def traceSnapshot (dur: Time): Trajectory

  /**
    * create a interpolated trajectory for the given date interval and delta
    * note that callers are responsible for reasonable start and end dates, to make sure
    * errors are acceptable if outside the datapoint interval
    */
  def interpolate (start: DateTime, end: DateTime, dt: Time)
                  (createInterpolant: (Trajectory)=>TInterpolant[N3,TDP3]): Trajectory

  /**
    * returns a new MutableTrajectory object holding the current state
    */
  def branch: MutTrajectory

  /**
    * returns a empty mutable trajectory based on concrete type
    */
  def emptyMutable (initCapacity: Int): MutTrajectory

  /**
    * return data point at mid index
    * note this is not the mid-point in terms of time or position
    */
  def arithmeticMidDataPoint: TDP3 = dataPoint(size/2)

  /**
    * compute heading and speed at given time
    * if date is before first or equal or greater than last data point return values of first or last leg
    *
    * note - this means caller might have to check if provided time is covered by trajectory
    */
  def computeVelocity2D (d: DateTime,
                         computeDistance: (GeoPosition,GeoPosition)=>Length,
                         computeHeading: (GeoPosition,GeoPosition)=>Angle
                    ): Velocity2D = {
    if (size < 2) { // not enough data points
      new Velocity2D(Angle.UndefinedAngle, Speed.UndefinedSpeed)
    }

    var i0 = 0
    val i = indexOf(d)

    if (i <= 0) {
      i0 = 0
    } else if (i == size-1) {
      i0 = i - 1
    } else {
      i0 = i
    }

    val i1 = i0 + 1
    val p0 = dataPoint(i0)
    val p1 = dataPoint(i1)

    val hdg = computeHeading(p0,p1)
    val spd = computeDistance(p0,p1) / p1.epochMillis.timeSince(p0.epochMillis)

    new Velocity2D(hdg, spd)
  }

  /**
    * for debugging purposes
    */
  def dump(): Unit = {
    var i = 0
    println("Trajectory(")
    foreach (new TDP3){ p=>
      if (i > 0) println(',')
      print(s"  (${p.millis}, ${p._0}, ${p._1}, ${p._2})")
      i += 1
    }
    println("\n)")
  }
}


/**
  * public interface for a mutable trajectory that supports adding/removing track points
  *
  * default implementation uses basic unit value types, but interface includes higher level track points.
  * Override more abstract methods in case those are stored directly, in order to avoid extra allocation
  */
trait MutTrajectory extends Trajectory {

  // basic type appender
  def append(date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit

  //--- override if any of the types are stored directly
  def append (p: TrackPoint): Unit = {
    val pos = p.position
    append(p.date, pos.φ, pos.λ, pos.altitude)
  }
  def append (date: DateTime, pos: GeoPosition): Unit = {
    append(date, pos.φ, pos.λ, pos.altitude)
  }
  def append (p: TDP3): Unit = {
    append(p.epochMillis,p.φ, p.λ, p.altitude)
  }

  def appendIfNew (p: TrackPoint): Unit = {
    if (size == 0 || p.date.toEpochMillis > getLastDate.toEpochMillis) append(p)
  }
  def appendIfNew (date: DateTime, pos: GeoPosition): Unit = {
    if (size == 0 || date.toEpochMillis > getLastDate.toEpochMillis) append(date,pos)
  }
  def appendIfNew (p: TDP3): Unit = {
    if (size == 0 || p.epochMillis > getLastDate) append(p)
  }

  def += (p: TrackPoint): Unit = append(p)
  def += (p: TDP3): Unit = append(p)

  def ++= (ps: IterableOnce[TrackPoint]): Unit = ps.iterator.foreach(append)
  def ++= (ps: Iterator[TDP3]): Unit = ps.foreach(append)

  def clear: Unit
  def drop (n: Int): Unit
  def dropRight (n: Int): Unit
}

/**
  * common storage-independent parts of concrete Trajectories
  * this is an internal implementation type
  */
trait Traj extends Trajectory {

  //--- the storage interface (mixed-in by storage traits or provided by concrete type)
  protected def getDateMillis(i: Int): Long
  protected def getTDP3(i: Int, dp: TDP3): TDP3
  protected def update(i: Int, date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit
  protected def getTrackPoint (i: Int): TrackPoint
  protected def updateMutTrackPoint (p: MutTrajectoryPoint) (i: Int): TrackPoint
  protected def updateTDP3 (dp: TDP3) (i: Int): TDP3 = getTDP3(i,dp)
  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit

  //--- generic implementations of the Trajectory interface (based on storage interface

  override def getT(i: Int): Long = getDateMillis(i) // TDataSource interface

  override def getDataPoint(i: Int, dp: TDP3): TDP3 = getTDP3(i,dp) // TDataSource interface

  override def getFirstDate: DateTime = if (nonEmpty) DateTime.ofEpochMillis(getDateMillis(0)) else DateTime.UndefinedDateTime
  override def getLastDate: DateTime = if (nonEmpty) DateTime.ofEpochMillis(getDateMillis(size-1)) else DateTime.UndefinedDateTime

  override def apply(i: Int): TrackPoint = {
    if (i < 0 || i >= size) throw new IndexOutOfBoundsException(s"track point index out of range: $i")
    getTrackPoint(i)
  }

  override def dataPoint (i: Int): TDP3 = {
    if (i < 0 || i >= size) throw new IndexOutOfBoundsException(s"track point index out of range: $i")
    getTDP3(i, new TDP3)
  }

  override def iterator: Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0, size)(getTrackPoint)
  }

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0, size)(updateMutTrackPoint(p))
  }

  override def iterator(p: TDP3): Iterator[TDP3] = {
    new CountUpIterator[TDP3](0, size)(updateTDP3(p))
  }

  override def reverseIterator: Iterator[TrackPoint] = {
    val len = size
    new CountDownIterator[TrackPoint](len-1, len)(getTrackPoint)
  }

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    val len = size
    new CountDownIterator[TrackPoint](len-1, len)(updateMutTrackPoint(p))
  }

  override def reverseIterator(p: TDP3): Iterator[TDP3] = {
    val len = size
    new CountDownIterator[TDP3](len-1, len)(updateTDP3(p))
  }

  override def copyToArrays(t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    val len = size
    var i = 0
    var j = 0
    while (i < len){
      updateUOMArrayElements(i, j, t, lat, lon, alt)
      i += 1
      j += 1
    }
  }

  def getDurationStartIndex(dur: Time): Int = {
    val dFirst = getFirstDate
    val dLast = getLastDate

    if (nonEmpty){
      if (dLast.timeSince(dFirst) <= dur) {
        0
      } else {
        var i = size - 2
        while (i >= 0) {
          val d = DateTime.ofEpochMillis(getDateMillis(i))
          if (dLast.timeSince(d) <= dur) {
            i -= 1
          }
        }
        i + 1
      }
    } else -1
  }

  // this is the generic implementation
  def interpolate (start: DateTime, end: DateTime, dt: Time)
                  (createInterpolant: (Trajectory)=>TInterpolant[N3,TDP3]): Trajectory = {
    val traj = emptyMutable( (end.timeSince(start) / dt).round.toInt + 1)
    val intr = createInterpolant(this)
    traj ++= intr.iterator(start.toEpochMillis, end.toEpochMillis, dt.toMillis)
    traj
  }
}

/**
  * common storage independent part of mutable trajectories
  */
trait MutTraj extends MutTrajectory with Traj {
  protected var _capacity: Int
  protected[trajectory] var _size: Int = 0

  protected def grow(newCapacity: Int): Unit // storage dependent
  protected def update(i: Int, date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit // storage dependent

  override def size = _size
  def capacity = _capacity

  protected def ensureSize (n: Int): Unit = {
    if (n > capacity) {
      var newCapacity = _capacity * 2
      while (newCapacity < n) newCapacity *= 2
      if (newCapacity > Int.MaxValue) newCapacity = Int.MaxValue // should probably be an exception
      grow(newCapacity)
      _capacity = newCapacity
    }
  }

  override def append(date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit = {
    ensureSize(_size + 1)
    update( _size, date,lat,lon,alt)
    _size += 1
  }

  override def clear: Unit = {
    _size = 0
  }

  override def drop(n: Int): Unit = {
    if (_size > n) {
      val i = n-1
      _size -= n

      //... adjust storage arrays here
    } else {
      _size = 0
    }
  }

  override def dropRight(n: Int): Unit = {
    if (_size > n) {
      _size -= n
    } else {
      _size = 0
    }
  }
}

/**
  * common storage independent parts of trajectory traces (implemented as CircularSeq)
  *
  * offset parameters represent logical indices (starting with 0 for the first stored track point),
  * NOT storage indices
  *
  * this is an internal implementation type
  */
trait TraceTraj extends MutTrajectory with Traj with CircularSeq {

  override def getT(off: Int): Long = {
    if (off >= _size || off < 0) throw new IndexOutOfBoundsException(s"time value index out of range: $off")
    getDateMillis(circularOffsetIdx(off))
  }

  override def append(date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit = {
    update( incIndices, date,lat,lon,alt)
  }

  override def apply (off: Int): TrackPoint = {
    if (off >= _size || off < 0) throw new IndexOutOfBoundsException(s"datapoint index out of range: $off")
    getTrackPoint(circularOffsetIdx(off))
  }

  override def getDataPoint (off: Int, p: TDP3): TDP3 = {
    if (off >= _size || off < 0) throw new IndexOutOfBoundsException(s"datapoint index out of range: $off")
    getTDP3(circularOffsetIdx(off),p)
  }

  override def iterator: Iterator[TrackPoint] = new ForwardIterator(getTrackPoint)

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = new ForwardIterator(updateMutTrackPoint(p))

  override def iterator(p: TDP3): Iterator[TDP3] = new ForwardIterator(updateTDP3(p))

  override def reverseIterator: Iterator[TrackPoint] = new ReverseIterator(getTrackPoint)

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = new ReverseIterator(updateMutTrackPoint(p))

  override def reverseIterator(p: TDP3): Iterator[TDP3] = new ReverseIterator(updateTDP3(p))

  override def copyToArrays(t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    var j = 0
    var i = tail
    while (i < head){
      updateUOMArrayElements(i % capacity, j, t, lat, lon, alt)
      j += 1
      i += 1
    }
  }

  override def getFirstDate: DateTime = {
    if (tail >= 0) DateTime.ofEpochMillis(getDateMillis(circularIdx(tail))) else DateTime.UndefinedDateTime
  }
  override def getLastDate: DateTime = {
    if (head >= 0) DateTime.ofEpochMillis(getDateMillis(circularIdx(head))) else DateTime.UndefinedDateTime
  }

  /**
    * note - this returns a logical index (0..head), not a physical (0..capacity) one
    */
  override def getDurationStartIndex(dur: Time): Int = {
    val dFirst = getFirstDate
    val dLast = getLastDate

    if (nonEmpty){
      if (dLast.timeSince(dFirst) <= dur) {
        tail
      } else {
        var i = head - 1
        while (i >= tail) {
          val d = DateTime.ofEpochMillis(getDateMillis(circularIdx(i)))
          if (dLast.timeSince(d) > dur) return i + 1
          i -= 1
        }
        throw new RuntimeException("inconsistent trajectory data")
      }
    } else -1
  }
}

trait ArrayTraj[T] extends Traj {
  this: T =>

  protected[trajectory] def copyArraysFrom (other: T, srcIdx: Int, dstIdx: Int, len: Int): Unit

  protected def snap (iStart: Int, iEnd: Int, create: (Int)=>ArrayTraj[T]): Trajectory = {
    val len = iEnd - iStart + 1
    val o = create(len)
    o.copyArraysFrom(this, iStart, 0, len)
    o
  }
}

trait TraceArrayTraj[T] extends TraceTraj with ArrayTraj[T] {
  this: T => // apparently self types are not inherited

  override protected def snap (iStart: Int, iEnd: Int, create: (Int)=>ArrayTraj[T]): Trajectory = {
    val hIdx = circularIdx(iEnd)
    val tIdx = circularIdx(iStart)
    val len = iEnd - iStart + 1
    val o = create(len)
    if (hIdx >= tIdx) {
      o.copyArraysFrom(this,tIdx,0,len)
    } else {
      val n = capacity - tIdx
      o.copyArraysFrom(this,tIdx ,0, n)
      o.copyArraysFrom(this,0, n, hIdx+1)
    }
    o
  }
}