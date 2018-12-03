/*
 * Copyright (c) 2018, United States Government, as represented by the
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
  * a decorator to filter which updates should be stored in a underlying Trajectory
  */
trait TrajectoryFilter extends Trajectory {
  var traj: Trajectory  // the underlying trajectory, provided by the concrete type (needs to be var since add can change type)

  protected def pass (t: Long, lat: Double, lon: Double, alt: Double): Boolean

  override def addPre(t: Long, lat: Double, lon: Double, alt: Double): Trajectory = {
    if (pass(t,lat,lon,alt)) {

      traj = traj.addPre(t,lat,lon,alt)
    }
    this
  }

  override def isEmpty = traj.isEmpty
  override def nonEmpty = traj.nonEmpty
  override def capacity = traj.capacity
  override def size = traj.size

  override def foreachPre(f: (Int,Long,Double,Double,Double)=>Unit) = traj.foreachPre(f)
  override def foreachPreReverse(f: (Int,Long,Double,Double,Double)=>Unit) = traj.foreachPreReverse(f)
}

/**
  * a TrajectoryFilter that filters entries which are less than dtMillis apart
  */
class TimedTrajectoryFilter (var traj: Trajectory, val dtMillis: Long) extends TrajectoryFilter {
  protected var tLastMillis: Long = -dtMillis*2 // something that makes the first test pass

  override protected def pass (t: Long, lat: Double, lon: Double, alt: Double): Boolean = {
    if ((t - tLastMillis) >= dtMillis) {
      tLastMillis = t
      true
    } else false
  }
}
