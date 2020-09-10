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
import gov.nasa.race.common.{AsciiBuffer, ByteSlice, ConstAsciiSlice, UTF8XmlPullParser2}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.optional
import gov.nasa.race.track.{TrackInfo, TrackInfoReader, TrackInfos}
import gov.nasa.race.trajectory.{MutTrajectory, MutUSTrajectory}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.DateTime.Date0
import gov.nasa.race.uom.Length.UndefinedLength

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

/**
  * XmlPullParser2 based parser to extract TrackInfo related information from tfmDataService messages
  *
  * note - ds:tfmDataService messages are just one potential source of TrackInfos
  */
class TfmTrackInfoParser2(val config: Config=NoConfig) extends UTF8XmlPullParser2 with ConfigurableTranslator with TrackInfoReader {

  // constant tag and attr names
  val tfmDataService = ConstAsciiSlice("ds:tfmDataService")
  val fltdMessage = ConstAsciiSlice("fdm:fltdMessage")
  val airport = ConstAsciiSlice("nxce:airport")
  val etdType = ConstAsciiSlice("etdType")
  val etaType = ConstAsciiSlice("etaType")
  val timeValue = ConstAsciiSlice("timeValue")
  val actual = ConstAsciiSlice("ACTUAL")
  val estimated = ConstAsciiSlice("ESTIMATED")
  val equipmentQualifier = ConstAsciiSlice("equipmentQualifier")

  // only created on-demand if we have to parse strings
  lazy protected val bb = new AsciiBuffer(config.getIntOrElse("buffer-size", 120000))

  override def translate(src: Any): Option[Any] = {
    val tInfos = readMessage(src)
    if (tInfos.nonEmpty) Some(TrackInfos(tInfos)) else None
  }


  // the TrackInfoReader interface (TODO - unify)
  override def readMessage (msg: Any): Seq[TrackInfo] = {
    msg match {
      case s: String =>
        bb.encode(s)
        parseTracks(bb.data, 0, bb.len)
      case Some(s: String) =>
        bb.encode(s)
        parseTracks(bb.data, 0, bb.len)
      case s: ByteSlice =>
        parseTracks(s.data,s.off,s.limit)
      case bs: Array[Byte] =>
        parseTracks(bs,0,bs.length)
      case _ => Seq.empty[TrackInfo] // unsupported input
    }
  }

  def parseTracks(bs: Array[Byte], off: Int, limit: Int): Seq[TrackInfo] = {
    if (initialize(bs,off,limit)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == tfmDataService) {
            return parseTfmDataService
          }
        }
      }
    }
    Seq.empty[TrackInfo]
  }

  def parseTracks (slice: ByteSlice): Seq[TrackInfo] = parseTracks(slice.data,slice.off,slice.limit)

  protected def parseTfmDataService: Seq[TrackInfo] = {
    val tInfos = new ArrayBuffer[TrackInfo](20)

    while (parseNextTag) {
      if (isStartTag) {
        if (tag == fltdMessage) parseFltdMessage(tInfos)
      } else { // end tag
        if (tag == tfmDataService) return tInfos
      }
    }
    tInfos
  }

  protected def parseFltdMessage (tInfos: ArrayBuffer[TrackInfo]): Unit = {
    // TODO get acId, arrApt, depApt from element tag attributes
    // "nxce:aircraftId" "nxce:igtd" "nxce:departurePoint" "nxce:arrivalPoint" "nxcm:etd" "nxcm:eta" "nxcm:newFlightAircraftSpecs" "nxce:waypoint"

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
        val off = attrName.off
        val len = attrName.len

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
        } else if (attrName == timeValue) dt = DateTime.parseYMDTSlice(attrValue)
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
          dt = DateTime.parseYMDTSlice(attrValue)
        }
      }

      if (isEstimate) eta = dt else ata = dt
    }

    def readAircraftSpecs: Unit = {
      if (parseAttr(equipmentQualifier)) trackCat = attrValue.intern
      trackType = readStringContent
    }

    def fltdMessage (data: Array[Byte], off: Int, len: Int): Unit = {
      @inline def process_nxce$aircraftId = if (cs == null) cs = readInternedStringContent
      @inline def process_nxce$arrivalPoint = if (arrArpt == null) parseElement(arrivalAirport)
      @inline def process_nxce$departurePoint = if (depArpt == null) parseElement(departureAirport)
      @inline def process_nxce$igtd = atd = DateTime.parseYMDTSlice(readSliceContent)  // initial gate time of departure (TODO not sure if we should use this as a etd or atd proxy)
      @inline def process_nxce$waypoint = readWaypoint
      @inline def process_nxcm$etd = readEtd
      @inline def process_nxcm$eta = readEta
      @inline def process_nxcm$newFlightAircraftSpecs = readAircraftSpecs

      @inline def match_nxc = { len>=3 && data(off)==110 && data(off+1)==120 && data(off+2)==99 }
      @inline def match_nxce$ = { len>=5 && data(off+3)==101 && data(off+4)==58 }
      @inline def match_nxce$a = { len>=6 && data(off+5)==97 }
      @inline def match_nxce$aircraftId = { len==15 && data(off+6)==105 && data(off+7)==114 && data(off+8)==99 && data(off+9)==114 && data(off+10)==97 && data(off+11)==102 && data(off+12)==116 && data(off+13)==73 && data(off+14)==100 }
      @inline def match_nxce$arrivalPoint = { len==17 && data(off+6)==114 && data(off+7)==114 && data(off+8)==105 && data(off+9)==118 && data(off+10)==97 && data(off+11)==108 && data(off+12)==80 && data(off+13)==111 && data(off+14)==105 && data(off+15)==110 && data(off+16)==116 }
      @inline def match_nxce$igtd = { len==9 && data(off+5)==105 && data(off+6)==103 && data(off+7)==116 && data(off+8)==100 }
      @inline def match_nxce$departurePoint = { len==19 && data(off+5)==100 && data(off+6)==101 && data(off+7)==112 && data(off+8)==97 && data(off+9)==114 && data(off+10)==116&& data(off+11)==117 && data(off+12)==114 && data(off+13)==101 && data(off+14)==80 && data(off+15)==111 && data(off+16)==105 && data(off+17)==110 && data(off+18)==116 }
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
          } else if (match_nxce$igtd) {
            process_nxce$igtd
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
