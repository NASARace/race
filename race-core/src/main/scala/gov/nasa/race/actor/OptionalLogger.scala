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
package gov.nasa.race.actor

import gov.nasa.race._
import gov.nasa.race.core.PublishingRaceActor
import gov.nasa.race.config.ConfigUtils._

/**
  * a PublishingRaceActor that can write to a optional log channel
  */
trait OptionalLogger extends PublishingRaceActor {

  val writeToLog = config.getOptionalString("write-to-log")
  val maxLog = config.getIntOrElse("max-log",100)
  var nLog = 0

  def publishToLogChannel (msg: Any) = {
    ifSome(writeToLog) { chan =>
      if (nLog < maxLog) {
        nLog += 1
        publish(chan, msg)
      }
    }
  }

  def hasLogChannel = writeToLog.isDefined
}
