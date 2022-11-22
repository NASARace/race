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
import gov.nasa.race.earth.ViirsHotspotArchiveReader
import gov.nasa.race.uom.Time
import gov.nasa.race.uom.Time.Seconds

import scala.concurrent.duration.DurationInt

/**
  * a hotspot replay actor that emits hotspots at the recorded acquisition time.
  * Note this is not corresponding to live import since the satellites have polar orbits and there is a delay
  * between overpass times and availability of active fire data products
  *
  * Hotspot replay actors are a bit different in that they accumulate older entries that still fall into the
  * configured history duration, i.e. the first entry might have more data
  */
class InTimeHotspotReplayActor (val config: Config) extends Replayer {
  type R = ViirsHotspotArchiveReader
  override def createReader = new ViirsHotspotArchiveReader(config)

  // how far we reach back for the first entry
  val hotspotHistory: Time = Seconds(config.getFiniteDurationOrElse("hotspot.history", 7.days).toSeconds)

  override protected def skipEntry (e: ArchiveEntry): Boolean = {
    e.date < baseSimTime - hotspotHistory
  }
}
