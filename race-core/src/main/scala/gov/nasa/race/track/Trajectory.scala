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
package gov.nasa.race.track

/**
  * abstraction for trajectories that does not imply underlying representation, allowing for memory
  * optimized implementations
  */
trait Trajectory {
  def capacity: Int
  def size: Int

  def add (lat: Double, lon: Double, alt: Double, t: Long): Trajectory // low level add to avoid temporary objects
  def add (tp: TrackPoint3D): Trajectory  // can be chained
  def += (tp: TrackPoint3D) = add(tp)

  def addAll (tps: Seq[TrackPoint3D]): Unit = tps.foreach(add)  // can be overridden for more efficient version
  def ++= (tps: Seq[TrackPoint3D]) = addAll(tps)

  def nonEmpty = size > 0

  /** low level iteration support that does not require temporary objects for FlightPath elements
    * The provided function takes 5 arguments:
    *   Int - path element index
    *   Double,Double - lat,lon in Degrees
    *   Double - alt in meters
    *   Long - epoch millis
    */
  def foreach(f: (Int,Double,Double,Double,Long) => Unit)
}

object EmptyTrajectory extends Trajectory {
  override def capacity = 0
  override def size = 0
  override def add (lat: Double, lon: Double, alt: Double, t: Long) = new CompactTrajectory().add(lat,lon,alt,t)
  override def add(tp: TrackPoint3D) = new CompactTrajectory().add(tp)
  override def foreach(f: (Int,Double,Double,Double,Long) => Unit) = {}
}