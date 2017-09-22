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
package gov.nasa.race.core

import akka.actor.Actor

/**
  * an actor that manages discrete time for its subordinates
  * this is an Actor and not a RaceActor since it is also mixed into the Master
  *
  * note that we don't need to keep a sorted list of event times since we only care for the next one (min value)
  * each of the controlled actors is supposed to remember its next event time and is only notified of the
  * discrete clock advances (event times might change due to prior events of other actors anyways). This forces us
  * to get responses from all child actors prior to each step to announce their next scheduled time, but this seems
  * necessary anyways to make sure we don't miss an actor that is still busy in the current step
  */
trait DiscreteTimeManager extends Actor {

  var nextEventTime: Long


}
