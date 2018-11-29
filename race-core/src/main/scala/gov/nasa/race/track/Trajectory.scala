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

  /** low level iteration support that does not require temporary objects for FlightPath elements
    * The provided function takes 5 arguments:
    *   Int - path element index
    *   Double,Double - lat,lon in Degrees
    *   Double - alt in meters
    *   Long - epoch millis
    */
  def foreach(f: (Int,Double,Double,Double,Long)=>Unit): Unit
  def foreachReverse(f: (Int,Double,Double,Double,Long)=>Unit): Unit

  //--- methods that can be overridden but have a generic implementation

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size > 0

  def add (e: TrackPoint): Trajectory = {
    val pos = e.position
    add(pos.φ.toDegrees,pos.λ.toDegrees,pos.altitude.toMeters,e.date.getMillis)
  }
  def += (tp: TrackPoint) = add(tp)

  def addAll (tps: Seq[TrackPoint]): Unit = tps.foreach(add)  // can be overridden for more efficient version
  def ++= (tps: Seq[TrackPoint]): Unit = addAll(tps)

}

object EmptyTrajectory extends Trajectory {
  override def capacity = 0
  override def size = 0
  override def add (lat: Double, lon: Double, alt: Double, t: Long) = new LossyTrajectory().add(lat,lon,alt,t)
  override def add(tp: TrackPoint) = new LossyTrajectory().add(tp)
  override def foreach(f: (Int,Double,Double,Double,Long) => Unit) = {}
  override def foreachReverse(f: (Int,Double,Double,Double,Long) => Unit) = {}
}