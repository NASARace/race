/*
 * Copyright (c) 2023, United States Government, as represented by the
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
import gov.nasa.race.geo.BoundingBoxGeoFilter
import gov.nasa.race.uom.DateTime

import java.io.File

/**
 * event indicating availability of a wind field data file with associated forecast and reference time stamps, plus
 * information about respective forecast area and data types (grid, vector etc.)
 */
case class WindFieldAvailable ( area: String,                   // name of the area
                                bounds: BoundingBoxGeoFilter,   // geographic boundaries
                                wfType: String,                 // wind field type (vector, grid, contour,.. - (used by client)
                                wfSrs: String,                  // spec of spatial reference system
                                baseDate: DateTime,             // time on which this forecast is based
                                forecastDate: DateTime,         // time this forecast is for
                                file: File                      // file that holds the data
                              ) extends FileAvailable {
  def date: DateTime = forecastDate


  def toJsonWithUrl (url: String): String = {
    s"""{"windField":{"area":"$area","bounds":${bounds.toJson2D},"forecastDate":${forecastDate.toEpochMillis},"baseDate":${baseDate.toEpochMillis},"wfType":"$wfType","wfSrs":"$wfSrs","url":"$url"}"""
  }
}
