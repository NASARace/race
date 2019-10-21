/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.air.translator

import com.typesafe.config.Config
import gov.nasa.race.common.StringXmlPullParser2
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.track.TrackInfo

import scala.collection.mutable.ArrayBuffer

/**
  * XmlPullParser2 based parser to extract TrackInfo related information from tfmDataService messages
  */
class TfmTrackInfoParser (val config: Config=NoConfig)
  extends StringXmlPullParser2(config.getIntOrElse("buffer-size",200000)) with ConfigurableTranslator {

  val tfmDataService = Slice("ds:tfmDataService")
  val fltdMessage = Slice("fdm:fltdMessage")

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String => parse(s)
      case Some(s: String) => parse(s)
      case _ => None // nothing else supported yet
    }
  }

  protected def parse (msg: String): Option[Any] = {
    if (initialize(msg)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == tfmDataService) return parseTfmDataService
        }
      }
    }
    None
  }

  protected def parseTfmDataService: Option[Any] = {
    val tInfos = new ArrayBuffer[TrackInfo](20)

    while (parseNextTag) {
      if (isStartTag) {
        if (tag == fltdMessage) parseFltdMessage(tInfos)
      } else { // end tag
        if (tag == tfmDataService) {
          if (tInfos.nonEmpty) return Some(tInfos.toSeq) else None
        }
      }
    }
    None
  }

  protected def parseFltdMessage (tInfos: ArrayBuffer[TrackInfo]): Unit = {
    // TODO get acId, arrApt, depApt from element tag attributes
    // "nxce:aircraftId"  "nxce:departurePoint" "nxce:arrivalPoint" "nxcm:etd" "nxcm:eta" "nxcm:newFlightAircraftSpecs" "nxce:waypoint"
  }
}
