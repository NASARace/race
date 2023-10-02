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
package gov.nasa.race.earth
import gov.nasa.race.common.FileAvailable
import gov.nasa.race.uom.DateTime

import java.io.File

case class SmokeAvailable(satellite: String,              // satellite source, either goes-18 or goes-17
                          smokeFile: File,                // file that holds the smoke or cloud contours
                          cloudFile: File,
                          srs: String,                    // spec of spatial reference system
                          date: DateTime,                 // datetime of the satellite imagery used to generate the file
                         )  {

  def toJsonWithUrl (url: String): String = {
    s"""{"smokeLayer":{"satellite":"$satellite","date":${date.toEpochMillis},"srs":"$srs", "url":"$url"}}"""
  }

  def toJsonWithTwoUrls (smokeUrl: String, cloudUrl: String, id:String): String = {
    s"""{"smokeLayer":{"id": "$id", "satellite":"$satellite","date":${date.toEpochMillis},"srs":"$srs", "smokeUrl":"$smokeUrl", "cloudUrl":"$cloudUrl"}}"""
  }
}
