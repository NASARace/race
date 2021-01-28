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

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.DateTime.ofEpochMillis
import gov.nasa.race.uom.{Angle, DateTime, Length, Time}

/**
  * a filter for adding track points
  * this can be used to filter out out-of-order or duplicated track points, but could also
  * check if the last track point could be replaced if linear interpolation yields a sufficiently close location
  *
  * Note that we chose this over a MutTrajectory decorator since we are only interested in intercepting
  * 3 methods. A decorator could not easily handle heterogeneous append data, would have to override branch
  * in the concrete type, and - most importantly - would incur runtime overhead for apply(),getT() and
  * getDataPoint(). The downside of this approach is that clients have to maintain TrajectoryFilter objects
  * explicitly, but this might be required anyways since only clients know about potential anomalies
  * of their input channels
  */
abstract class TrajectoryFilter (val traj: MutTrajectory) {
  def append(date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit
  def append (p: TrackPoint): Unit
  def append (date: DateTime, pos: GeoPosition): Unit
}

class TimeFilter (t: MutTrajectory, dt: Time) extends TrajectoryFilter(t) {
  protected var lastDate: DateTime = DateTime.Date0 // don't use UndefinedDateTime as timeUntil/Since would overflow

  override def append (date: DateTime, lat: Angle, lon: Angle, alt: Length): Unit = {
    if (lastDate.timeUntil(date) >= dt) {
      traj.append(date,lat,lon,alt)
      lastDate = date
    }
  }

  def append (p: TrackPoint): Unit = {
    val d = p.date
    if (lastDate.timeUntil(d) >= dt) {
      traj.append(p)
      lastDate = d
    }
  }

  def append (date: DateTime, pos: GeoPosition): Unit = {
    if (lastDate.timeUntil(date) >= dt) {
      traj.append(date,pos)
      lastDate = date
    }
  }
}
