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
import gov.nasa.race.common.{TDataPoint3, TDataSource}
import gov.nasa.race.geo.{GeoPosition, LatLonPos, MutLatLonPos}
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Time, Angle, AngleArray, Length, LengthArray, TimeArray}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import org.joda.time.{DateTime, MutableDateTime, ReadableDateTime}

/**
  * immutable object holding trajectory point information
  */
class TrajectoryPoint (val date: DateTime, val position: GeoPosition) extends TrackPoint {
  def this (t: Time, lat: Angle, lon: Angle, alt: Length) = this(t.toDateTime, LatLonPos(lat,lon,alt))

  def setTDataPoint3(p: TDataPoint3): Unit = {
    p.update(date.getMillis, position.latDeg, position.lonDeg, position.altMeters)
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
    p.update(date.getMillis, position.latDeg, position.lonDeg, position.altMeters)
  }

  def toTrajectoryPoint: TrajectoryPoint = new TrajectoryPoint(new DateTime(date.getMillis), position.toLatLonPos)
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


  def toTrajectoryPoint = new TrajectoryPoint(new DateTime(millis), LatLonPos(Degrees(_0),Degrees(_1),Meters(_2)))

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

  def reverseIterator: Iterator[TrackPoint]
  def reverseIterator (p: MutTrajectoryPoint): Iterator[TrackPoint]


  def copyToArrays (t: TimeArray, lat: AngleArray, lon: AngleArray, alt: LengthArray)

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
  def dropWhile (p: (TrackPoint)=>Boolean)
  def dropRight (n: Int)
}