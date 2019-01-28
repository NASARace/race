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
import gov.nasa.race.geo.{GeoPosition, MutLatLonPos}
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Angle, Length}
import org.joda.time.{DateTime, MutableDateTime}

/**
  * immutable object holding trajectory point information
  */
class TrajectoryPoint (val date: DateTime, val position: GeoPosition) extends TrackPoint

/**
  * mutable object holding trajectory point information
  * can be used to sequentially iterate through trajectory without allocation
  */
class MutTrajectoryPoint (val date: MutableDateTime, val position: MutLatLonPos) extends TrackPoint {
  def update (millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    date.setMillis(millis)
    position.update(lat,lon,alt)
  }
}

/**
  * root interface for trajectories
  * implementation does not imply mutability, storage (direct, compressed, local) or coverage (full, trace)
  */
trait Trajectory extends TDataSource[N3,TDataPoint3] {

  def capacity: Int  // max size (might be dynamically adapted if mutable)
  def size: Int

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size > 0

  /**
    * trajectory point access with logical index [0..size-1]
    * throws ArrayIndexOutOfBounds exception if outside limits
    */
  def apply (i: Int): TrajectoryPoint

  /**
    * allocation free forward iteration
    * function parameters are epoch millis, latitude, longitude and altitude
    */
  def foreach(g: (Long,Angle,Angle,Length)=>Unit): Unit

  def iterator: Iterator[TrajectoryPoint]
  def iterator (tp: MutTrajectoryPoint): Iterator[MutTrajectoryPoint]

  /**
    * allocation free backwards iteration
    * function parameters are epoch millis, latitude, longitude and altitude
    */
  def foreachReverse(g: (Long, Angle,Angle,Length)=>Unit)

  def reverseIterator: Iterator[TrajectoryPoint]
  def reverseIterator (tp: MutTrajectoryPoint): Iterator[MutTrajectoryPoint]

  /**
    * copy this trajectory into client managed predefined type arrays
    * this is the most basic, least type safe representation for expensive trajectory computations
    * @param t    trackpoint times in epoch millis
    * @param lat  trackpoint latitudes in degrees
    * @param lon  trackpoint longitudes in degrees
    * @param alt  trackpoint altitudes in meters
    */
  def copyToMillisDegreesMeters (t: Array[Long], lat: Array[Double], lon: Array[Double], alt: Array[Double])

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
  * a mutable trajectory that supports adding/clearing points
  */
trait MutTrajectory extends Trajectory {

  def append (millis: Long, lat: Angle, lon: Angle, alt: Length): Unit
  def += (p: TrackPoint): MutTrajectory.this.type

  def clear: Unit
}