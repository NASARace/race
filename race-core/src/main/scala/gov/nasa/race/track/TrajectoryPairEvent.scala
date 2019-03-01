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

import gov.nasa.race.trajectory.{Trajectory => Traj}

/**
  * an event that involves a pair of trajectories
  */
case class TrajectoryPairEvent (id: String,                 // of event, not tracks
                                eventType: String,          // description (short)
                                track1: TrackedObject,      // first involved track
                                trajectory1: Traj,
                                track2: TrackedObject,      // second involved track
                                trajectory2: Traj,
                                eventDetails: Option[Any] = None
                               )
