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

import gov.nasa.race.common.BufferedStringXmlPullParser2
import gov.nasa.race.config.ConfigurableTranslator
import gov.nasa.race.IdentifiableObject
import gov.nasa.race.air.FlightPos
import gov.nasa.race.common._
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.{TrackCompleted, TrackedObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import gov.nasa.race.uom.DateTime
import com.typesafe.config.Config

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

/**
  * optimized translator for SFDPS MessageCollection (and legacy NasFlight) messages
  *
  * matcher parts are automatically generated by .tool.StringSetMatcher with "script/smg gen <tag> ..."
  */
class MessageCollectionParser(val config: Config=NoConfig)
             extends UTF8XmlPullParser2 with ConfigurableTranslator {

  protected var flights: ArrayBuffer[IdentifiableObject] = null // this can also include TrackCompleted objects
  protected var reUseFlights: Boolean = false
  def setFlightsReUsable (cond: Boolean) = reUseFlights = cond

  //--- attributes and ancestor elements
  val messageCollection = Slice("ns5:MessageCollection")
  val nasFlight = Slice("ns5:NasFlight")
  val aircraftIdentification = Slice("aircraftIdentification")
  val enRoute = Slice("enRoute")
  val location = Slice("location")
  val positionTime = Slice("positionTime")
  val flight = Slice("flight")
  val actualSpeed = Slice("actualSpeed")
  val uom = Slice("uom")
  val knots = Slice("KNOTS")
  val mph = Slice("MPH")
  val kmh = Slice("KMH")
  val feet = Slice("FEET")
  val meters = Slice("METERS")
  val arrival = Slice("arrival")
  val arrivalPoint = Slice("arrivalPoint")
  val time = Slice("time")

  val slicer = new SliceSplitter(' ')

  // only created on-demand if we have to parse strings
  lazy protected val bb = new ASCIIBuffer(config.getIntOrElse("buffer-size", 120000))

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String =>
        bb.encode(s)
        parse(bb.data, 0, bb.length)
      case Some(s: String) =>
        bb.encode(s)
        parse(bb.data, 0, bb.length)
      case s: Slice =>
        parse(s.data,s.offset,s.limit)
      case bs: Array[Byte] =>
        parse(bs,0,bs.length)
      case _ => None // unsupported input
    }
  }

  def parse (bs: Array[Byte], off: Int, limit: Int): Option[Any] = {
    if (initialize(bs,off,limit)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == messageCollection) parseMessageCollection
          else if (tag == nasFlight) parseNasFlight
        }
      }

      if (flights.nonEmpty) Some(flights) else None

    } else {
      None
    }
  }

  //--- basic data parse methods that do not wrap results (collections) into Option

  def parseFlights (bs: Array[Byte], off: Int, limit: Int): Seq[IdentifiableObject] = {
    if (initialize(bs,off,limit)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == messageCollection) parseMessageCollection
          else if (tag == nasFlight) parseNasFlight
        }
      }

      if (flights.nonEmpty) flights else Seq.empty[IdentifiableObject]

    } else {
      Seq.empty[TrackedObject]
    }
  }

  def parseFlights (s: Slice): Seq[IdentifiableObject] = parseFlights(s.data,s.offset,s.limit)

  protected def parseMessageCollection: Unit = {
    clearFlights
    while (parseNextTag) {
      if (isStartTag) {
        if (tag == flight) parseFlight
      }
    }
  }

  protected def parseNasFlight: Unit = {
    clearFlights
    parseFlight
  }

  protected def clearFlights: Unit = {
    if (flights != null && reUseFlights) {
      flights.clear
    } else {
      flights = new ArrayBuffer[IdentifiableObject](120)
    }
  }

  protected def parseFlight: Unit = {
    var id, cs: String = null
    var lat, lon, vx, vy: Double = UndefinedDouble
    var alt: Length = UndefinedLength
    var spd: Speed = UndefinedSpeed
    var vr: Speed = UndefinedSpeed // there is no vertical rate in FIXM_v3_2
    var date, arrivalDate: DateTime = DateTime.UndefinedDateTime
    var arrPt: String = null

    def flight (data: Array[Byte], off: Int, len: Int): Unit = {
      if (isStartTag) {
        //--- matcher generated by gov.nasa.race.tool.StringMatchGenerator

        def readSpeed: Speed = {
          val u = if (parseAttr(uom)) attrValue else Slice.EmptySlice

          if (parseSingleContentString) {
            val v = contentString.toDouble
            if (u == mph) UsMilesPerHour(v)
            else if (u == kmh) KilometersPerHour(v)
            else Knots(v)

          } else UndefinedSpeed
        }

        def readAltitude: Length = {
          val u = if (parseAttr(uom)) attrValue else Slice.EmptySlice

          if (parseSingleContentString) {
            val v = contentString.toDouble
            if (u == meters) Meters(v) else Feet(v)
          } else UndefinedLength
        }

        @inline def process_flightIdentification = {
          while ((id == null || cs == null) && parseNextAttr) {
            val off = attrName.offset
            val len = attrName.length

            @inline def match_computerId = {
              len == 10 && data(off) == 99 && data(off + 1) == 111 && data(off + 2) == 109 && data(off + 3) == 112 && data(off + 4) == 117 && data(off + 5) == 116 && data(off + 6) == 101 && data(off + 7) == 114 && data(off + 8) == 73 && data(off + 9) == 100
            }

            @inline def match_aircraftIdentification = {
              len == 22 && data(off) == 97 && data(off + 1) == 105 && data(off + 2) == 114 && data(off + 3) == 99 && data(off + 4) == 114 && data(off + 5) == 97 && data(off + 6) == 102 && data(off + 7) == 116 && data(off + 8) == 73 && data(off + 9) == 100 && data(off + 10) == 101 && data(off + 11) == 110 && data(off + 12) == 116 && data(off + 13) == 105 && data(off + 14) == 102 && data(off + 15) == 105 && data(off + 16) == 99 && data(off + 17) == 97 && data(off + 18) == 116 && data(off + 19) == 105 && data(off + 20) == 111 && data(off + 21) == 110
            }

            if (match_computerId) {
              id = attrValue.intern
            } else if (match_aircraftIdentification) {
              cs = attrValue.intern
            }
          }
        }

        @inline def process_pos = {
          if (tagHasParent(location)) {
            if (parseSingleContentString) {
              slicer.setSource(contentString)
              if (slicer.hasNext) lat = slicer.next.toDouble
              if (slicer.hasNext) lon = slicer.next.toDouble
            }
          }
        }

        @inline def process_position = {
          if (tagHasParent(enRoute)) {
            if (parseAttr(positionTime)) date = DateTime.parseYMDT(attrValue)
          }
        }

        @inline def process_x = vx = readDoubleContent // we ignore uom since we just use content value to compute heading
        @inline def process_y = vy = readDoubleContent

        @inline def process_surveillance = if (tagHasParent(actualSpeed)) spd = readSpeed

        @inline def process_altitude = alt = readAltitude

        @inline def process_arrival = if (parseAttr(arrivalPoint)) arrPt = attrValue.intern

        @inline def process_actual = {
          if (tagHasAncestor(arrival)) {
            if (parseAttr(time)) arrivalDate = DateTime.parseYMDT(attrValue)
          }
        }

        @inline def match_flightIdentification = {len == 20 && data(off) == 102 && data(off + 1) == 108 && data(off + 2) == 105 && data(off + 3) == 103 && data(off + 4) == 104 && data(off + 5) == 116 && data(off + 6) == 73 && data(off + 7) == 100 && data(off + 8) == 101 && data(off + 9) == 110 && data(off + 10) == 116 && data(off + 11) == 105 && data(off + 12) == 102 && data(off + 13) == 105 && data(off + 14) == 99 && data(off + 15) == 97 && data(off + 16) == 116 && data(off + 17) == 105 && data(off + 18) == 111 && data(off + 19) == 110}
        @inline def match_pos = {len >= 3 && data(off) == 112 && data(off + 1) == 111 && data(off + 2) == 115}
        @inline def match_pos_len = {len == 3}
        @inline def match_position = {len == 8 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 105 && data(off + 6) == 111 && data(off + 7) == 110}
        @inline def match_x = {len == 1 && data(off) == 120}
        @inline def match_y = {len == 1 && data(off) == 121}
        @inline def match_surveillance = {len == 12 && data(off) == 115 && data(off + 1) == 117 && data(off + 2) == 114 && data(off + 3) == 118 && data(off + 4) == 101 && data(off + 5) == 105 && data(off + 6) == 108 && data(off + 7) == 108 && data(off + 8) == 97 && data(off + 9) == 110 && data(off + 10) == 99 && data(off + 11) == 101}
        @inline def match_a = {len >= 1 && data(off) == 97}
        @inline def match_altitude = {len == 8 && data(off + 1) == 108 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 117 && data(off + 6) == 100 && data(off + 7) == 101}
        @inline def match_arrival = {len == 7 && data(off + 1) == 114 && data(off + 2) == 114 && data(off + 3) == 105 && data(off + 4) == 118 && data(off + 5) == 97 && data(off + 6) == 108}
        @inline def match_actual = {len == 6 && data(off + 1) == 99 && data(off + 2) == 116 && data(off + 3) == 117 && data(off + 4) == 97 && data(off + 5) == 108}

        if (match_flightIdentification) {
          process_flightIdentification
        } else if (match_pos) {
          if (match_pos_len) {
            process_pos
          } else if (match_position) {
            process_position
          }
        } else if (match_x) {
          process_x
        } else if (match_y) {
          process_y
        } else if (match_surveillance) {
          process_surveillance
        } else if (match_a) {
          if (match_altitude) {
            process_altitude
          } else if (match_arrival) {
            process_arrival
          } else if (match_actual) {
            process_actual
          }
        }
      }
    }

    parseElement(flight)

    if (cs != null) {
      if (arrivalDate.isDefined) {
        // this is reported as a separate event since we normally don't get positions for completes
        flights += TrackCompleted(id, cs, arrPt, arrivalDate)
      } else {
        if (lat.isDefined && lon.isDefined && date.isDefined &&
          vx.isDefined && vy.isDefined && spd.isDefined && alt.isDefined) {
          val fpos = new FlightPos(id, cs, GeoPosition(Degrees(lat),Degrees(lon),alt),
            spd, Degrees(Math.atan2(vx, vy).toDegrees), vr, date)
          flights += fpos
        } else {
          //println(s"@@@ rejected flight: $cs $lat $lon $date $vx $vy $spd $alt")
        }
      }
    }
  }
}
