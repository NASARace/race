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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.UTF8XmlPullParser2
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.{Angle, DateTime, Length}
import gov.nasa.race.whileSome

import scala.collection.mutable.ArrayBuffer

object GpxParser {

  //--- lexical constants
  val GPX = asc("gpx")
  val NAME = asc("name")
  val DESC = asc("desc")
  val TRK = asc("trk")
  val TRKSEG = asc("trkseg")
  val TRKPT = asc("trkpt")
  val LAT = asc("lat")
  val LON = asc("lon")
  val ELE = asc("ele")
  val TIME = asc("time")
}
import GpxParser._

/**
  * parser for *.gpx files:
  *
  * <?xml version="1.0"?>
  * <gpx version="1.1" creator="Avenza Maps - https://www.avenzamaps.com/maps/how-it-works.html"
  *       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://www.topografix.com/GPX/1/1"
  *       xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  *   <trk>
  *     <name><![CDATA[Track 1]]></name>
  *     <desc><![CDATA[Description:]]></desc>
  *     <trkseg>
  *        <trkpt lat="37.24493853" lon="-122.14486312">
  *            <ele>693.162</ele>
  *            <time>2020-08-22T13:13:12-07:00</time>
  *        </trkpt>...
  */
class GpxParser extends UTF8XmlPullParser2 {
  protected var name: String = ""
  protected var desc: String = ""

  def parseHeader (bs: Array[Byte], off: Int, length: Int): Boolean = {
    if (initialize(bs,off,length)) {
      if (parseNextTag && isStartTag && tag == GPX) {
        return true
      }
    }
    false
  }

  def parseHeader (bs: Array[Byte]): Boolean = parseHeader(bs,0,bs.length)

  def parseHeader (s: String): Boolean = parseHeader(s.getBytes)

  def parseToNextTrack(): Boolean = {
    while (parseNextTag && isStartTag && tag != TRK){}  // skip over wpt and rte
    while (parseNextTag) {
      if (isStartTag){
        if (tag == NAME) name = readCdataContent
        else if (tag == DESC) desc = readCdataContent
        else if (tag == TRKSEG) return true
      }
    }
    false
  }

  def parseNextTrackPoint(): Option[GpsPos] = {
    var lat = Angle.UndefinedAngle
    var lon = Angle.UndefinedAngle
    var ele = Length.UndefinedLength
    var date = DateTime.UndefinedDateTime

    while (parseNextTag) {
      if (isStartTag) {
        tag match {
          case TRKPT =>
            while (parseNextAttr) {
              if (attrName == LAT) lat = Degrees(attrValue.toDouble)
              else if (attrName == LON) lon = Degrees(attrValue.toDouble)
            }
          case ELE => ele = Meters(readDoubleContent)
          case TIME => date = readDateTimeContent
        }
      } else { // end tag
        if (tag == TRKPT) {
          if (lat.isDefined && lon.isDefined && ele.isDefined && date.isDefined) {
            return Some( new GpsPos(name,date,GeoPosition(lat,lon,ele)))
          }
        } else if (tag == TRK) return None
      }
    }
    None
  }

  def parseTrackPoints(): Seq[GpsPos] = {
    val buf = ArrayBuffer.empty[GpsPos]
    whileSome(parseNextTrackPoint()) { buf.append }
    buf.toSeq
  }
}
