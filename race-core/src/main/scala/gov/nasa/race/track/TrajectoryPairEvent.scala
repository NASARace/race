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

import gov.nasa.race.geo.{GeoPosition, GeoPositioned}
import gov.nasa.race.trajectory.Trajectory
import org.joda.time.DateTime

/**
  * an event that involves a pair of trajectories
  * note that this is also a generic TrackPoint
  *
  * TODO - unify this with ProximityEvent
  */
case class TrajectoryPairEvent (id: String, // of event, not tracks
                                date: DateTime,
                                position: GeoPosition,
                                eventType: String,
                                eventDetails: String,

                                //--- specifics
                                track1: TrackedObject, // first involved track
                                pos1: GeoPosition, // at time of event
                                trajectory1: Trajectory,

                                track2: TrackedObject, // second involved track
                                pos2: GeoPosition, // at time of event
                                trajectory2: Trajectory
                               ) extends TrackEvent with TrackPoint with GeoPositioned
