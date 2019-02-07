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
import gov.nasa.race.common.{CircularSeq, CountDownIterator, CountUpIterator, TDataPoint3, TDataSource}
import gov.nasa.race.geo.{GeoPosition, LatLonPos, MutLatLonPos}
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Angle, AngleArray, Date, DateArray, Length, LengthArray, TimeArray}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import org.joda.time.{MutableDateTime, ReadableDateTime, DateTime => JodaDateTime}

/**
  * immutable object holding trajectory point information
  */
class TrajectoryPoint (val date: JodaDateTime, val position: GeoPosition) extends TrackPoint {
  def this (t: JodaDateTime, lat: Angle, lon: Angle, alt: Length) = this(t.toDateTime, LatLonPos(lat,lon,alt))

  def setTDataPoint3(p: TDataPoint3): Unit = {
    p.set(date.getMillis, position.latDeg, position.lonDeg, position.altMeters)
  }
}

/**
  * mutable object holding trajectory point information
  * can be used to sequentially iterate through trajectory without allocation
  */
class MutTrajectoryPoint (val date: MutableDateTime, val position: MutLatLonPos) extends TrackPoint {
  def this() = this(new MutableDateTime(0), new MutLatLonPos(Angle0,Angle0,Length0))

  def update (millis: Long, lat: Angle, lon: Angle, alt: Length): this.type = {
    date.setMillis(millis)
    position.update(lat,lon,alt)
    this
  }

  def updateTDataPoint3(p: TDataPoint3): Unit = {
    p.set(date.getMillis, position.latDeg, position.lonDeg, position.altMeters)
  }

  def toTrajectoryPoint: TrajectoryPoint = new TrajectoryPoint(new JodaDateTime(date.getMillis), position.toLatLonPos)
}

/**
  * the low level type version of a TrajectoryPoint which is compatible with TInterpolant/TDataSource
  * but adds uom accessors and high level conversion
  */
class TDP3 (_millis: Long, _lat: Double, _lon: Double, _alt: Double)
                                            extends TDataPoint3(_millis,_lat,_lon,_alt) with GeoPosition {
  override def φ: Angle = Degrees(_0)
  def φ_= (lat: Angle): Unit = _0 = lat.toDegrees

  override def λ: Angle = Degrees(_1)
  def λ_= (lon: Angle): Unit = _1 = lon.toDegrees

  override def altitude: Length = Meters(_2)
  def altitude_= (alt: Length): Unit = _2 = alt.toMeters


  def toTrajectoryPoint = new TrajectoryPoint(new JodaDateTime(millis), LatLonPos(Degrees(_0),Degrees(_1),Meters(_2)))

  def updateTrajectoryPoint (p: MutTrajectoryPoint): Unit = {
    p.update(millis, Degrees(_0), Degrees(_1), Meters(_2))
  }
}

/**
  * root interface for trajectories
  *
  * note that implementation does not imply mutability, storage (direct, compressed, local)
  * or coverage (full, trace). We only use a abstract TrackPoint result type
  */
trait Trajectory extends TDataSource[N3,TDP3] {

  def capacity: Int  // max size (might be dynamically adapted if mutable)
  def size: Int

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size > 0

  def newDataPoint: TDP3 = new TDP3(0,0,0,0)

  /**
    * trajectory point access with logical index [0..size-1]
    * throws ArrayIndexOutOfBounds exception if outside limits
    */
  def apply (i: Int): TrackPoint

  def iterator: Iterator[TrackPoint]
  def iterator (p: MutTrajectoryPoint): Iterator[TrackPoint]

  def foreach (f: (TrackPoint)=>Unit): Unit = {
    val it = iterator
    while (it.hasNext) f(it.next)
  }
  def foreach (p: MutTrajectoryPoint)(f: (TrackPoint)=>Unit): Unit = {
    val it = iterator(p)
    while (it.hasNext) f(it.next)
  }

  def reverseIterator: Iterator[TrackPoint]
  def reverseIterator (p: MutTrajectoryPoint): Iterator[TrackPoint]

  def foreachReverse (f: (TrackPoint)=>Unit): Unit = {
    val it = reverseIterator
    while (it.hasNext) f(it.next)
  }
  def foreachReverse (p: MutTrajectoryPoint)(f: (TrackPoint)=>Unit): Unit = {
    val it = reverseIterator(p)
    while (it.hasNext) f(it.next)
  }

  def copyToArrays (t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray)

  /**
    * return a new Trajectory object that preserves the current state (immutable)
    */
  def snapshot: Trajectory

  /**
    * returns a new MutableTrajectory object holding the current state
    */
  def branch: MutTrajectory
}


/**
  * a mutable trajectory that supports adding/removing track points
  *
  * default implementation uses basic unit value types, but interface includes higher level track points.
  * Override more abstract methods in case those are stored directly, in order to avoid extra allocation
  */
trait MutTrajectory extends Trajectory {

  // basic type appender
  def append (millis: Long, lat: Angle, lon: Angle, alt: Length): Unit

  //--- override if any of the types are stored directly
  def append (p: TrackPoint): Unit = {
    val pos = p.position
    append(p.date.getMillis, pos.φ, pos.λ, pos.altitude)
  }
  def append (date: ReadableDateTime, pos: GeoPosition): Unit = {
    append(date.getMillis, pos.φ, pos.λ, pos.altitude)
  }
  def += (p: TrackPoint): Unit = append(p)
  def ++= (ps: Seq[TrackPoint]): Unit = ps.foreach(append)

  def clear: Unit
  def drop (n: Int)
  def dropRight (n: Int)
}

/**
  * common storage-independent parts of concrete Trajectories
  * this is an internal implementation type
  */
trait IndexedTraj extends Trajectory {

  //--- the storage interface (mixed-in by storage traits or provided by concrete type)
  protected def update(i: Int, millis: Long, lat: Angle, lon: Angle, alt: Length): Unit
  protected def getTrackPoint (i: Int): TrackPoint
  protected def updateMutTrackPoint (p: MutTrajectoryPoint) (i: Int): TrackPoint
  protected def updateUOMArrayElements (i: Int, destIdx: Int,
                                        t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit

  //--- generic implementations of the Trajectory interface (based on storage interface

  override def apply(i: Int): TrackPoint = {
    if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i)
    getTrackPoint(i)
  }

  override def iterator: Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0, size)(getTrackPoint)
  }

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    new CountUpIterator[TrackPoint](0, size)(updateMutTrackPoint(p))
  }

  override def reverseIterator: Iterator[TrackPoint] = {
    val len = size
    new CountDownIterator[TrackPoint](len-1, len)(getTrackPoint)
  }

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = {
    val len = size
    new CountDownIterator[TrackPoint](len-1, len)(updateMutTrackPoint(p))
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
}

/**
  * common storage independent parts of trajectory traces (implemented as CircularSeq)
  *
  * offset parameters represent logical indices (starting with 0 for the first stored track point),
  * NOT storage indices
  *
  * this is an internal implementation type
  */
trait TrajTrace extends MutTrajectory with IndexedTraj with CircularSeq {

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

  override def copyToArrays(t: DateArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = {
    var j = 0
    var i = tail
    while (i < head){
      updateUOMArrayElements(i % capacity, j, t, lat, lon, alt)
      j += 1
      i += 1
    }
  }
}