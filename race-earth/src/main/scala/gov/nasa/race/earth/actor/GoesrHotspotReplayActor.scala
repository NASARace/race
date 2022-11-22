/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.earth.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.Replayer
import gov.nasa.race.archive.ArchiveEntry
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.earth.GoesrHotspotArchiveReader
import gov.nasa.race.uom.Time
import gov.nasa.race.uom.Time.Seconds

import scala.concurrent.duration.DurationInt

/**
 * replay actor for GOES-R CVS archives
 */
class GoesrHotspotReplayActor (val config: Config) extends Replayer {
  type R = GoesrHotspotArchiveReader

  override def createReader = new GoesrHotspotArchiveReader(config)

  // how far we reach back for the first entry
  val hotspotHistory: Time = Seconds(config.getFiniteDurationOrElse("history", 3.days).toSeconds)

  override protected def skipEntry (e: ArchiveEntry): Boolean = {
    e.date < baseSimTime - hotspotHistory
  }
}
