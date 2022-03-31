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
import gov.nasa.race.IdentifiableObject
import gov.nasa.race.air.{TfmTrack, TfmTracks}
import gov.nasa.race.common.{AsciiBuffer, ByteSlice, ConstAsciiSlice, Parser, UTF8XmlPullParser2}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.{MutSrcTracks, MutSrcTracksHolder, TrackedObject}
import gov.nasa.race.uom.Angle.{UndefinedAngle, _}
import gov.nasa.race.uom.Length.{UndefinedLength, _}
import gov.nasa.race.uom.Speed.{UndefinedSpeed, _}
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}

import scala.Double.NaN
import scala.collection.Seq

// we don't really have a source (yet) but keep it similar to SFDPS and TAIS
class TfmTracksImpl(initSize: Int) extends MutSrcTracks[TfmTrack](initSize) with TfmTracks

/**
  * a translator for tfmDataService (tfmdata) SWIM messages. We only process fltdMessage/trackInformation yet
  *
  * TODO - check if fltdMessage attributes are mandatory since we don't parse the respective trackInformation sub-elements
  */
class TfmDataServiceParser(val config: Config=NoConfig) extends UTF8XmlPullParser2
                with ConfigurableTranslator with Parser with MutSrcTracksHolder[TfmTrack,TfmTracksImpl] {

  val tfmDataService = ConstAsciiSlice("ds:tfmDataService")
  val fltdMessage = ConstAsciiSlice("fdm:fltdMessage")
  val trackInformation = ConstAsciiSlice("fdm:trackInformation")
  val reportedAltitude = ConstAsciiSlice("nxcm:reportedAltitude")
  val position = ConstAsciiSlice("nxcm:position")
  val airlineData = ConstAsciiSlice("nxcm:airlineData")
  val etaType = ConstAsciiSlice("etaType")
  val ncsmTrackData = ConstAsciiSlice("nxcm:ncsmTrackData")
  val ncsmRouteData = ConstAsciiSlice("nxcm:ncsmRouteData")
  val actualValue = ConstAsciiSlice("ACTUAL")
  val completedValue = ConstAsciiSlice("COMPLETED")
  val westValue = ConstAsciiSlice("WEST")
  val southValue = ConstAsciiSlice("SOUTH")
  val timeValue = ConstAsciiSlice("timeValue")

  // only created on-demand if we have to parse strings
  lazy protected val bb = new AsciiBuffer(config.getIntOrElse("buffer-size", 100000))

  override def createElements = new TfmTracksImpl(50)

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String =>
        bb.encode(s)
        parse(bb.data, 0, bb.len)
      case Some(s: String) =>
        bb.encode(s)
        parse(bb.data, 0, bb.len)
      case s: ByteSlice =>
        parse(s.data,s.off,s.limit)
      case bs: Array[Byte] =>
        parse(bs,0,bs.length)
      case _ => None // unsupported input
    }
  }

  def parse (bs: Array[Byte], off: Int, length: Int): Option[Any] = {
    parseTracks(bs, off, length)
    if (elements.nonEmpty) Some(elements) else None
  }

  def parseTracks (bs: Array[Byte], off: Int, length: Int): TfmTracks = {
    if (checkIfTfmDataService(bs,off,length)){
      parseTfmDataServiceInitialized
    } else {
      TfmTracks.empty
    }
  }

  def parseTracks(slice: ByteSlice): Seq[IdentifiableObject] = parseTracks(slice.data,slice.off,slice.len)

  def checkIfTfmDataService (bs: Array[Byte], off: Int, length: Int): Boolean = {
    if (initialize(bs,off,length)) {
      if (parseNextTag && isStartTag) return (tag == tfmDataService)
    }
    false
  }

  def parseTfmDataServiceInitialized: TfmTracks = {
    clearElements
    parseTfmDataService
    elements
  }

  protected def parseTfmDataService: Unit = {
    while (parseNextTag) {
      if (isStartTag) {
        if (tag == fltdMessage) parseFltdMessage
      }
    }
  }

  protected def parseFltdMessage: Unit = {

    var flightRef: String = "?"
    var cs: String = null
    var source: String = "?"
    var date: DateTime = DateTime.UndefinedDateTime
    var arrArpt: String = null
    var depArpt: String = null

    val data = this.data

    def processAttrs: Unit = {
      while (parseNextAttr) {
        val off = attrName.off
        val len = attrName.len

        @inline def process_acid = cs = attrValue.intern
        @inline def process_arrArpt = arrArpt = attrValue.intern
        @inline def process_depArpt = depArpt = attrValue.intern
        @inline def process_flightRef = flightRef = attrValue.intern
        @inline def process_sourceTimeStamp = date = DateTime.parseYMDTSlice(attrValue)
        @inline def process_sourceFacility = source = attrValue.intern

        @inline def match_a = { len>=1 && data(off)==97 }
        @inline def match_acid = { len==4 && data(off+1)==99 && data(off+2)==105 && data(off+3)==100 }
        @inline def match_arrArpt = { len==7 && data(off+1)==114 && data(off+2)==114 && data(off+3)==65 && data(off+4)==114 && data(off+5)==112 && data(off+6)==116 }
        @inline def match_depArpt = { len==7 && data(off)==100 && data(off+1)==101 && data(off+2)==112 && data(off+3)==65 && data(off+4)==114 && data(off+5)==112 && data(off+6)==116 }
        @inline def match_flightRef = { len==9 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116 && data(off+6)==82 && data(off+7)==101 && data(off+8)==102 }
        @inline def match_source = { len>=6 && data(off)==115 && data(off+1)==111 && data(off+2)==117 && data(off+3)==114 && data(off+4)==99 && data(off+5)==101 }
        @inline def match_sourceTimeStamp = { len==15 && data(off+6)==84 && data(off+7)==105 && data(off+8)==109 && data(off+9)==101 && data(off+10)==83 && data(off+11)==116 &&data(off+12)==97 && data(off+13)==109 && data(off+14)==112 }
        @inline def match_sourceFacility = { len==14 && data(off+6)==70 && data(off+7)==97 && data(off+8)==99 && data(off+9)==105 && data(off+10)==108 && data(off+11)==105 && data(off+12)==116 && data(off+13)==121 }
        if (match_a) {
          if (match_acid) {
            process_acid
          } else if (match_arrArpt) {
            process_arrArpt
          }
        } else if (match_depArpt) {
          process_depArpt
        } else if (match_flightRef) {
          process_flightRef
        } else if (match_source) {
          if (match_sourceTimeStamp) {
            process_sourceTimeStamp
          } else if (match_sourceFacility) {
            process_sourceFacility
          }
        }
      }
    }

    def parseTrackInformation: Unit = {

      //--- the values we collect per track
      var lat: Angle = UndefinedAngle
      var lon: Angle = UndefinedAngle
      var speed: Speed = UndefinedSpeed
      var alt: Length = UndefinedLength
      var nextWP: GeoPosition = null
      var nextWPDate: DateTime = DateTime.UndefinedDateTime
      var completed: Boolean = false

      def readAlt: Length = {
        val v = readSliceContent
        var factor = 100

        val bs = v.data
        var i = v.off
        val iEnd = v.off + v.len
        var d: Double = 0
        while (i<iEnd) {
          val b = bs(i)
          if (b >= '0' && b <= '9') d = d * 10 + (b - '0')
          else if (b == 'T') factor = 1000
          i += 1
        }
        Feet(d * factor)
      }

      def parseTrackData: Unit = {
        val endLevel = depth-1

        // parse "nxcm:eta" "nxcm:nextEvent"
        while (parseNextTag) {
          val off = tag.off
          val len = tag.len

          if (isStartTag) {

            @inline def process_nxcm$eta = nextWPDate = readNextWPDate
            @inline def process_nxcm$nextEvent = nextWP = readNextWP

            @inline def match_nxcm$ = { len>=5 && data(off)==110 && data(off+1)==120 && data(off+2)==99 && data(off+3)==109 && data(off+4)==58 }
            @inline def match_nxcm$eta = { len==8 && data(off+5)==101 && data(off+6)==116 && data(off+7)==97 }
            @inline def match_nxcm$nextEvent = { len==14 && data(off+5)==110 && data(off+6)==101 && data(off+7)==120 && data(off+8)==116 && data(off+9)==69 && data(off+10)==118 && data(off+11)==101 && data(off+12)==110 && data(off+13)==116 }

            if (match_nxcm$) {
              if (match_nxcm$eta) {
                process_nxcm$eta
              } else if (match_nxcm$nextEvent) {
                process_nxcm$nextEvent
              }
            }
          } else {
            if (depth == endLevel) return
          }
        }
      }

      def parseRouteData: Unit = {
        val endLevel = depth-1

        // parse "nxcm:eta" "nxcm:nextPosition"
        while (parseNextTag) {
          if (isStartTag) {
            val off = tag.off
            val len = tag.len

            @inline def process_nxcm$eta = nextWPDate = readNextWPDate
            @inline def process_nxcm$nextPosition = nextWP = readNextWP

            @inline def match_nxcm$ = { len>=5 && data(off)==110 && data(off+1)==120 && data(off+2)==99 && data(off+3)==109 && data(off+4)==58 }
            @inline def match_nxcm$eta = { len==8 && data(off+5)==101 && data(off+6)==116 && data(off+7)==97 }
            @inline def match_nxcm$nextPosition = { len==17 && data(off+5)==110 && data(off+6)==101 && data(off+7)==120 && data(off+8)==116 && data(off+9)==80 && data(off+10)==111 && data(off+11)==115 && data(off+12)==105 && data(off+13)==116 && data(off+14)==105 && data(off+15)==111 && data(off+16)==110 }

            if (match_nxcm$) {
              if (match_nxcm$eta) {
                process_nxcm$eta
              } else if (match_nxcm$nextPosition) {
                process_nxcm$nextPosition
              }
            }
          } else {
            if (depth == endLevel) return
          }
        }
      }

      def parseAirlineData: Unit = {
        val endLevel = depth-1

        while (parseNextTag) {
          if (isStartTag) {
            val off = tag.off
            val len = tag.len

            @inline def match_nxcm$eta = { len==8 && data(off)==110 && data(off+1)==120 && data(off+2)==99 && data(off+3)==109 && data(off+4)==58 && data(off+5)==101 && data(off+6)==116 && data(off+7)==97 }

            if (match_nxcm$eta) {
              while (parseNextAttr) {
                if (attrName == etaType){
                  if (!(attrValue == actualValue)) return
                } else if (attrName == timeValue) {
                  if (date.isUndefined) date =  DateTime.parseYMDTSlice(attrValue)
                  if (lat.isUndefined) lat = Angle0
                  if (lon.isUndefined) lon = Angle0
                }
              }
            }

          } else { // end tag
            if (depth == endLevel) return
          }
        }
      }

      def readNextWP: GeoPosition = {
        var lat: Double = NaN
        var lon: Double = NaN

        while (parseNextAttr) {
          val off = attrName.off
          val len = attrName.len

          @inline def process_latitudeDecimal = lat = attrValue.toDouble
          @inline def process_longitudeDecimal = lon = attrValue.toDouble

          @inline def match_l = { len>=1 && data(off)==108 }
          @inline def match_latitudeDecimal = { len==15 && data(off+1)==97 && data(off+2)==116 && data(off+3)==105 && data(off+4)==116 && data(off+5)==117 && data(off+6)==100 && data(off+7)==101 && data(off+8)==68 && data(off+9)==101 && data(off+10)==99 && data(off+11)==105 && data(off+12)==109 && data(off+13)==97 && data(off+14)==108 }
          @inline def match_longitudeDecimal = { len==16 && data(off+1)==111 && data(off+2)==110 && data(off+3)==103 && data(off+4)==105 && data(off+5)==116 && data(off+6)==117 && data(off+7)==100 && data(off+8)==101 && data(off+9)==68 && data(off+10)==101 && data(off+11)==99 && data(off+12)==105 && data(off+13)==109 && data(off+14)==97 && data(off+15)==108 }

          if (match_l) {
            if (match_latitudeDecimal) {
              process_latitudeDecimal
            } else if (match_longitudeDecimal) {
              process_longitudeDecimal
            }
          }
        }

        GeoPosition.fromDegrees(lat,lon)
      }

      def readNextWPDate: DateTime = {
        if (parseAttr(timeValue)) {
          DateTime.parseYMDTSlice(attrValue)
        } else DateTime.UndefinedDateTime
      }

      def parsePosition: Unit = {
        val endLevel = depth-1

        while (parseNextTag) {
          val off = tag.off
          val len = tag.len

          if (isStartTag) {
            @inline def process_nxce$latitudeDMS = lat = readDMS
            @inline def process_nxce$longitudeDMS = lon = readDMS

            @inline def match_nxce$l = { len>=6 && data(off)==110 && data(off+1)==120 && data(off+2)==99 && data(off+3)==101 && data(off+4)==58 && data(off+5)==108 }
            @inline def match_nxce$latitudeDMS = { len==16 && data(off+6)==97 && data(off+7)==116 && data(off+8)==105 && data(off+9)==116 && data(off+10)==117 && data(off+11)==100 && data(off+12)==101 && data(off+13)==68 && data(off+14)==77 && data(off+15)==83 }
            @inline def match_nxce$longitudeDMS = { len==17 && data(off+6)==111 && data(off+7)==110 && data(off+8)==103 && data(off+9)==105 && data(off+10)==116 && data(off+11)==117 && data(off+12)==100 && data(off+13)==101 && data(off+14)==68 && data(off+15)==77 && data(off+16)==83 }

            if (match_nxce$l) {
              if (match_nxce$latitudeDMS) {
                process_nxce$latitudeDMS
              } else if (match_nxce$longitudeDMS) {
                process_nxce$longitudeDMS
              }
            }
          } else {
            if (depth == endLevel) return
          }
        }
      }

      def readDMS: Angle = {
        var deg: Double = NaN
        var min: Double = NaN
        var sec: Double = 0
        var isNegative = false

        while (parseNextAttr) {
          val off = attrName.off
          val len = attrName.len

          @inline def process_degrees = deg = attrValue.toInt
          @inline def process_direction = isNegative = (attrValue == westValue || attrValue == southValue)
          @inline def process_minutes = min = attrValue.toInt
          @inline def process_seconds = sec = attrValue.toInt

          @inline def match_d = { len>=1 && data(off)==100 }
          @inline def match_degrees = { len==7 && data(off+1)==101 && data(off+2)==103 && data(off+3)==114 && data(off+4)==101 && data(off+5)==101 && data(off+6)==115 }
          @inline def match_direction = { len==9 && data(off+1)==105 && data(off+2)==114 && data(off+3)==101 && data(off+4)==99 && data(off+5)==116 && data(off+6)==105 && data(off+7)==111 && data(off+8)==110 }
          @inline def match_minutes = { len==7 && data(off)==109 && data(off+1)==105 && data(off+2)==110 && data(off+3)==117 && data(off+4)==116 && data(off+5)==101 && data(off+6)==115 }
          @inline def match_seconds = { len==7 && data(off)==115 && data(off+1)==101 && data(off+2)==99 && data(off+3)==111 && data(off+4)==110 && data(off+5)==100 && data(off+6)==115 }
          if (match_d) {
            if (match_degrees) {
              process_degrees
            } else if (match_direction) {
              process_direction
            }
          } else if (match_minutes) {
            process_minutes
          } else if (match_seconds) {
            process_seconds
          }
        }

        val d = deg + min/60.0 + sec/3600.0
        if (isNegative) Degrees(-d) else Degrees(d)
      }

      /*
         "nxcm:speed" "nxce:simpleAltitude" "nxcm:flightStatus" "nxcm:ncsmTrackData" "nxcm:ncsmRouteData" "nxcm:airlineData" "nxcm:position"
       */

      while (parseNextTag){
        val off = tag.off
        val len = tag.len

        if (isStartTag) {
          @inline def process_nxcm$speed = speed = UsMilesPerHour(readDoubleContent)
          @inline def process_nxcm$flightStatus = completed = (readSliceContent == completedValue)
          @inline def process_nxcm$ncsmTrackData = parseTrackData
          @inline def process_nxcm$ncsmRouteData = parseRouteData
          @inline def process_nxcm$airlineData = parseAirlineData
          @inline def process_nxcm$position = parsePosition
          @inline def process_nxce$simpleAltitude = if (tagHasAncestor(reportedAltitude)) alt = readAlt

          @inline def match_nxc = { len>=3 && data(off)==110 && data(off+1)==120 && data(off+2)==99 }
          @inline def match_nxcm$ = { len>=5 && data(off+3)==109 && data(off+4)==58 }
          @inline def match_nxcm$speed = { len==10 && data(off+5)==115 && data(off+6)==112 && data(off+7)==101 && data(off+8)==101 && data(off+9)==100 }
          @inline def match_nxcm$flightStatus = { len==17 && data(off+5)==102 && data(off+6)==108 && data(off+7)==105 && data(off+8)==103 && data(off+9)==104 && data(off+10)==116 && data(off+11)==83 && data(off+12)==116 && data(off+13)==97 && data(off+14)==116 && data(off+15)==117 && data(off+16)==115 }
          @inline def match_nxcm$ncsm = { len>=9 && data(off+5)==110 && data(off+6)==99 && data(off+7)==115 && data(off+8)==109 }
          @inline def match_nxcm$ncsmTrackData = { len==18 && data(off+9)==84 && data(off+10)==114 && data(off+11)==97 && data(off+12)==99 && data(off+13)==107 && data(off+14)==68 && data(off+15)==97 && data(off+16)==116 && data(off+17)==97 }
          @inline def match_nxcm$ncsmRouteData = { len==18 && data(off+9)==82 && data(off+10)==111 && data(off+11)==117 && data(off+12)==116 && data(off+13)==101 && data(off+14)==68 && data(off+15)==97 && data(off+16)==116 && data(off+17)==97 }
          @inline def match_nxcm$airlineData = { len==16 && data(off+5)==97 && data(off+6)==105 && data(off+7)==114 && data(off+8)==108 && data(off+9)==105 && data(off+10)==110 && data(off+11)==101 && data(off+12)==68 && data(off+13)==97 && data(off+14)==116 && data(off+15)==97 }
          @inline def match_nxcm$position = { len==13 && data(off+5)==112 && data(off+6)==111 && data(off+7)==115 && data(off+8)==105 && data(off+9)==116 && data(off+10)==105 && data(off+11)==111 && data(off+12)==110 }
          @inline def match_nxce$simpleAltitude = { len==19 && data(off+3)==101 && data(off+4)==58 && data(off+5)==115 && data(off+6)==105 && data(off+7)==109 && data(off+8)==112 && data(off+9)==108 && data(off+10)==101 && data(off+11)==65 && data(off+12)==108 && data(off+13)==116 && data(off+14)==105 && data(off+15)==116 && data(off+16)==117 && data(off+17)==100 && data(off+18)==101 }

          if (match_nxc) {
            if (match_nxcm$) {
              if (match_nxcm$speed) {
                process_nxcm$speed
              } else if (match_nxcm$flightStatus) {
                process_nxcm$flightStatus
              } else if (match_nxcm$ncsm) {
                if (match_nxcm$ncsmTrackData) {
                  process_nxcm$ncsmTrackData
                } else if (match_nxcm$ncsmRouteData) {
                  process_nxcm$ncsmRouteData
                }
              } else if (match_nxcm$airlineData) {
                process_nxcm$airlineData
              } else if (match_nxcm$position) {
                process_nxcm$position
              }
            } else if (match_nxce$simpleAltitude) {
              process_nxce$simpleAltitude
            }
          }


        } else { // end tag
          if (tag == trackInformation) {
            //println(s"@@@ trackInformation $cs: $date $lat $lon $alt $nextWP")
            if (cs != null && (completed ||
              (date.isDefined && lat.isDefined && lon.isDefined && alt.isDefined && nextWP != null))) {
              val status = if (completed) TrackedObject.CompletedFlag else TrackedObject.NoStatus
              val track = if (completed) {
                TfmTrack(flightRef,cs,GeoPosition(lat,lon,alt),speed,date,status,
                  source,None,DateTime.UndefinedDateTime)
              } else {
                TfmTrack(flightRef,cs,GeoPosition(lat,lon,alt),speed,date,status,
                  source,Some(nextWP),nextWPDate)
              }
              elements += track
            } else {
              //println(s"rejected $cs $date $lat $lon $alt $nextWP")
            }
            return
          }
        }
      }
    }

    processAttrs
    while (parseNextTag) {
      if (isStartTag) {
        if (tag == trackInformation) parseTrackInformation
      } else { // end tag
        if (tag == fltdMessage) return
      }
    }
  }

}
