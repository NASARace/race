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
import gov.nasa.race.space.OverpassRegion
import gov.nasa.race.space.SatelliteInfo.satelliteInfos

import scala.collection.immutable.ArraySeq

/**
 * replay actor for Aqua and Terra satellites. While these are not using VIIRS the file format is the same and
 * hence we can use the same support classes.
 *
 * Note that archives obtained from /usfs/api/area/csv/.. do contain data for both Aqua /and/ Terra, i.e. other
 * than for the JPSS satellites we have one replay actor for both
 *
 * note also that we need tle archives for both satellites, i.e. 'tle-archive' has to contain a list of pathnames
 *
 * note also that we should not use 'satId' from JpssActor here
 */
class ModisReplayActor  (val conf: Config) extends JpssReplayActor(conf) {

  override def defaultScanAngle = 55.0 // MODIS has a smaller scan angle

  override def getOverpassRegions: Seq[OverpassRegion] = tleMap.keys.toSeq.map( sid =>
    OverpassRegion(sid, satelliteInfos.get(sid).map(_.name), ArraySeq.unsafeWrapArray(overpassBounds))
  )
}
