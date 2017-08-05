/*
 * Copyright (c) 2016, United States Government, as represented by the
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
package gov.nasa.race.air

import gov.nasa.race.track.{TrackInfoSource, TrackInfoStore}
import gov.nasa.race.util.{XmlAttrProcessor, XmlPullParser}
import org.joda.time.DateTime

/**
  * a XML parser that creates and stores TrackInfos from tfmdata messages
  */
class TrackInfoTFMParser(store: TrackInfoStore)
            extends XmlPullParser with XmlAttrProcessor with TrackInfoSource {
  setBuffered(4096)

  def parse (tfmDataMsg: String) = {
    initialize(tfmDataMsg)

    def parseTimeValueAttr (typeAttr: String, typeVal: String,
                            equalAction: (DateTime)=>Unit, notEqualAction: (DateTime)=>Unit) = {
      var tv: String = null
      var date: DateTime = null
      processAttributes {
        case `typeAttr` => tv = value
        case "timeValue" => if (value != null) date = DateTime.parse(value)
      }
      if (tv == typeVal) equalAction(date) else notEqualAction(date)
    }

    try {
      while (parseNextElement()) {
        if (isStartElement) {
          tag match {
            case "fdm:fltdMessage" => // this starts a new entry
              resetVars
              trackRef = readAttribute("flightRef")
            case "nxce:aircraftId" =>
              cs = trimmedTextOrNull()
            case "nxce:airport" =>
              if (hasParent("nxce:departurePoint")) departurePoint = trimmedTextOrNull()
              else if (hasParent("nxce:arrivalPoint")) arrivalPoint = trimmedTextOrNull()
            case "nxcm:etd" => // ? "actual" estimated time ?
              parseTimeValueAttr("etdType", "ACTUAL", atd_=, etd_=)
            case "nxcm:eta" =>
              parseTimeValueAttr("etaType", "ESTIMATED", eta_=, ata_=)
            case "nxcm:newFlightAircraftSpecs" =>
              trackCat = attributeOrNull("specialAircraftQualifier")
              trackType = trimmedTextOrNull()
            case other => // ignore - this could be our extension point
          }

        } else {  // end element
          tag match {
            case "fdm:fltdMessage" => store.updateFrom(this)
            case other => // ignore
          }
        }
      }
    } catch {
      case t: Throwable => t.printStackTrace()
    }
  }
}