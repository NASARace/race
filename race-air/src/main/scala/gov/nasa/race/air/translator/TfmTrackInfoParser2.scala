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
import gov.nasa.race.optional
import gov.nasa.race.track.{TrackInfo, TrackInfos}
import gov.nasa.race.trajectory.{MutTrajectory, MutUSTrajectory}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.DateTime.Date0
import gov.nasa.race.uom.Length.UndefinedLength

import scala.collection.mutable.ArrayBuffer

/**
  * XmlPullParser2 based parser to extract TrackInfo related information from tfmDataService messages
  */
class TfmTrackInfoParser2(val config: Config=NoConfig)
  extends StringXmlPullParser2(config.getIntOrElse("buffer-size",200000)) with ConfigurableTranslator {

  // constant tag and attr names
  val tfmDataService = Slice("ds:tfmDataService")
  val fltdMessage = Slice("fdm:fltdMessage")
  val airport = Slice("nxce:airport")
  val etdType = Slice("etdType")
  val etaType = Slice("etaType")
  val timeValue = Slice("timeValue")
  val actual = Slice("ACTUAL")
  val estimated = Slice("ESTIMATED")
  val equipmentQualifier = Slice("equipmentQualifier")

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
          if (tInfos.nonEmpty) return Some(TrackInfos(tInfos)) else None
        }
      }
    }
    None
  }

  protected def parseFltdMessage (tInfos: ArrayBuffer[TrackInfo]): Unit = {
    // TODO get acId, arrApt, depApt from element tag attributes
    // "nxce:aircraftId"  "nxce:departurePoint" "nxce:arrivalPoint" "nxcm:etd" "nxcm:eta" "nxcm:newFlightAircraftSpecs" "nxce:waypoint"

    var cs: String = null
    var trackRef: String = null
    var arrArpt: String = null
    var depArpt: String = null
    var etd = DateTime.UndefinedDateTime
    var atd = DateTime.UndefinedDateTime
    var eta = DateTime.UndefinedDateTime
    var ata = DateTime.UndefinedDateTime
    var trackCat, trackType: String = null
    val route: MutTrajectory = new MutUSTrajectory(30)

    val data = this.data

    def fltdAttrs (data: Array[Byte], off: Int, len: Int): Unit = {
      // acid flightRef arrArpt depArpt

      @inline def process_acid = cs = attrValue.intern
      @inline def process_flightRef = trackRef = attrValue.intern
      @inline def process_arrArpt = arrArpt = attrValue.intern
      @inline def process_depArpt = depArpt = attrValue.intern

      @inline def match_a = { len>=1 && data(off)==97 }
      @inline def match_acid = { len==4 && data(off+1)==99 && data(off+2)==105 && data(off+3)==100 }
      @inline def match_arrArpt = { len==7 && data(off+1)==114 && data(off+2)==114 && data(off+3)==65 && data(off+4)==114 && data(off+5)==112 && data(off+6)==116 }
      @inline def match_flightRef = { len==9 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116 && data(off+6)==82 && data(off+7)==101 && data(off+8)==102 }
      @inline def match_depArpt = { len==7 && data(off)==100 && data(off+1)==101 && data(off+2)==112 && data(off+3)==65 && data(off+4)==114 && data(off+5)==112 && data(off+6)==116 }

      if (match_a) {
        if (match_acid) {
          process_acid
        } else if (match_arrArpt) {
          process_arrArpt
        }
      } else if (match_flightRef) {
        process_flightRef
      } else if (match_depArpt) {
        process_depArpt
      }
    }

    def arrivalAirport (data: Array[Byte], off: Int, len: Int) = {
      if (tag == airport) arrArpt = readInternedStringContent
    }

    def departureAirport (data: Array[Byte], off: Int, len: Int): Unit = {
      if (tag == airport) depArpt = readInternedStringContent
    }

    def readWaypoint: Unit = {
      var et: Int = 0
      var latDeg = Double.NaN
      var lonDeg = Double.NaN

      while (parseNextAttr) {
        val off = attrName.offset
        val len = attrName.length

        @inline def process_elaspedTime = et = attrValue.toInt
        @inline def process_latitudeDecimal = latDeg = attrValue.toDouble
        @inline def process_longitudeDecimal = lonDeg = attrValue.toDouble

        @inline def match_elaspedTime = {len == 11 && data(off) == 101 && data(off + 1) == 108 && data(off + 2) == 97 && data(off + 3) == 115 && data(off + 4) == 112 && data(off + 5) == 101 && data(off + 6) == 100 && data(off + 7) == 84 && data(off + 8) == 105 && data(off + 9) == 109 && data(off + 10) == 101}
        @inline def match_l = {len >= 1 && data(off) == 108}
        @inline def match_latitudeDecimal = {len == 15 && data(off + 1) == 97 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 117 && data(off + 6) == 100 && data(off + 7) == 101 && data(off + 8) == 68 && data(off + 9) == 101 && data(off + 10) == 99 && data(off + 11) == 105 && data(off + 12) == 109 && data(off + 13) == 97 && data(off + 14) == 108}
        @inline def match_longitudeDecimal = {len == 16 && data(off + 1) == 111 && data(off + 2) == 110 && data(off + 3) == 103 && data(off + 4) == 105 && data(off + 5) == 116 && data(off + 6) == 117 && data(off + 7) == 100 && data(off + 8) == 101 && data(off + 9) == 68 && data(off + 10) == 101 && data(off + 11) == 99 && data(off + 12) == 105 && data(off + 13) == 109 && data(off + 14) == 97 && data(off + 15) == 108}

        if (match_elaspedTime) {
          process_elaspedTime
        } else if (match_l) {
          if (match_latitudeDecimal) {
            process_latitudeDecimal
          } else if (match_longitudeDecimal) {
            process_longitudeDecimal
          }
        }
      }

      if (!latDeg.isNaN && !lonDeg.isNaN) {
        route.append(Date0, Degrees(latDeg), Degrees(lonDeg), UndefinedLength) // TODO - use elapsed time
      }
    }

    def readEtd: Unit = {
      var isEstimate = false
      var dt = DateTime.UndefinedDateTime
      while (parseNextAttr) {
        if (attrName == etdType) {
          if (attrValue == estimated) isEstimate = true
        } else if (attrName == timeValue) dt = DateTime.parseYMDT(attrValue)
      }

      if (isEstimate) etd = dt else atd = dt
    }

    def readEta: Unit = {
      var isEstimate = true
      var dt = DateTime.UndefinedDateTime
      while (parseNextAttr) {
        if (attrName == etaType) {
          if (attrValue == actual) isEstimate = false
        } else if (attrName == timeValue) {
          dt = DateTime.parseYMDT(attrValue)
        }
      }

      if (isEstimate) eta = dt else ata = dt
      //println(s"@@@ $cs -> $eta")
    }

    def readAircraftSpecs: Unit = {
      if (parseAttr(equipmentQualifier)) trackCat = attrValue.intern
      trackType = readStringContent
    }

    def fltdMessage (data: Array[Byte], off: Int, len: Int): Unit = {
      @inline def process_nxce$aircraftId = if (cs == null) cs = readInternedStringContent
      @inline def process_nxce$arrivalPoint = if (arrArpt == null) parseElement(arrivalAirport)
      @inline def process_nxce$departurePoint = if (depArpt == null) parseElement(departureAirport)
      @inline def process_nxce$waypoint = readWaypoint
      @inline def process_nxcm$etd = readEtd
      @inline def process_nxcm$eta = readEta
      @inline def process_nxcm$newFlightAircraftSpecs = readAircraftSpecs

      @inline def match_nxc = { len>=3 && data(off)==110 && data(off+1)==120 && data(off+2)==99 }
      @inline def match_nxce$ = { len>=5 && data(off+3)==101 && data(off+4)==58 }
      @inline def match_nxce$a = { len>=6 && data(off+5)==97 }
      @inline def match_nxce$aircraftId = { len==15 && data(off+6)==105 && data(off+7)==114 && data(off+8)==99 && data(off+9)==114 && data(off+10)==97 && data(off+11)==102 && data(off+12)==116 && data(off+13)==73 && data(off+14)==100 }
      @inline def match_nxce$arrivalPoint = { len==17 && data(off+6)==114 && data(off+7)==114 && data(off+8)==105 && data(off+9)==118 && data(off+10)==97 && data(off+11)==108 && data(off+12)==80 && data(off+13)==111 && data(off+14)==105 && data(off+15)==110 && data(off+16)==116 }
      @inline def match_nxce$departurePoint = { len==19 && data(off+5)==100 && data(off+6)==101 && data(off+7)==112 && data(off+8)==97 && data(off+9)==114 && data(off+10)==116 && data(off+11)==117 && data(off+12)==114 && data(off+13)==101 && data(off+14)==80 && data(off+15)==111 && data(off+16)==105 && data(off+17)==110 && data(off+18)==116 }
      @inline def match_nxce$waypoint = { len==13 && data(off+5)==119 && data(off+6)==97 && data(off+7)==121 && data(off+8)==112 && data(off+9)==111 && data(off+10)==105 && data(off+11)==110 && data(off+12)==116 }
      @inline def match_nxcm$ = { len>=5 && data(off+3)==109 && data(off+4)==58 }
      @inline def match_nxcm$et = { len>=7 && data(off+5)==101 && data(off+6)==116 }
      @inline def match_nxcm$etd = { len==8 && data(off+7)==100 }
      @inline def match_nxcm$eta = { len==8 && data(off+7)==97 }
      @inline def match_nxcm$newFlightAircraftSpecs = { len==27 && data(off+5)==110 && data(off+6)==101 && data(off+7)==119 && data(off+8)==70 && data(off+9)==108 && data(off+10)==105 && data(off+11)==103 && data(off+12)==104 && data(off+13)==116 && data(off+14)==65 && data(off+15)==105 && data(off+16)==114 && data(off+17)==99 && data(off+18)==114 && data(off+19)==97 && data(off+20)==102 && data(off+21)==116 && data(off+22)==83 && data(off+23)==112 && data(off+24)==101 && data(off+25)==99 && data(off+26)==115 }

      if (match_nxc) {
        if (match_nxce$) {
          if (match_nxce$a) {
            if (match_nxce$aircraftId) {
              process_nxce$aircraftId
            } else if (match_nxce$arrivalPoint) {
              process_nxce$arrivalPoint
            }
          } else if (match_nxce$departurePoint) {
            process_nxce$departurePoint
          } else if (match_nxce$waypoint) {
            process_nxce$waypoint
          }
        } else if (match_nxcm$) {
          if (match_nxcm$et) {
            if (match_nxcm$etd) {
              process_nxcm$etd
            } else if (match_nxcm$eta) {
              process_nxcm$eta
            }
          } else if (match_nxcm$newFlightAircraftSpecs) {
            process_nxcm$newFlightAircraftSpecs
          }
        }
      }
    }

    parseAttrs(fltdAttrs)
    parseElementStartTags(fltdMessage)

    val ti = new TrackInfo( trackRef, cs,
      optional(trackCat), optional(trackType),
      optional(depArpt), optional(arrArpt),
      etd, atd, eta, ata,
      if (route.nonEmpty) Some(route.snapshot) else None)

    tInfos += ti
  }
}
