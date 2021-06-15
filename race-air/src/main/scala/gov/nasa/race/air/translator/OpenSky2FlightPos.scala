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
import gov.nasa.race.air.{FlightPos, FlightPosSeq, MutFlightPosSeqImpl}
import gov.nasa.race.common.{ConstAsciiSlice, UTF8JsonPullParser, Utf8Buffer}
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.MutSrcTracksHolder
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Speed._
//import io.circe.{Decoder, HCursor, Json, parser}

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

object OpenSkyParser {
  val _states_ = ConstAsciiSlice("states")
  val _time_ = ConstAsciiSlice("time")
}
import OpenSkyParser._

/**
  * a Json parser for OpenSky JSON query results as documented in https://opensky-network.org/apidoc/rest.html
  *
  * using opensky-net json format as defined in: https://opensky-network.org/apidoc/rest.html
  * {
  *   "time": 1568090280,
  *   "states": [
  *      ["4b1803", "SWR5181 ", "Switzerland", 1568090266, 1568090279, 8.5594, 47.4533, 9197.34,
  *       true, 0, 5, null, null, null, "1021", false, 0], ...
  *    ]
  * }
  *
  * this parser returns (potentially empty) FlightPos lists
  */
class OpenSkyParser extends UTF8JsonPullParser with MutSrcTracksHolder[FlightPos,MutFlightPosSeqImpl] {

  override def createElements = new MutFlightPosSeqImpl(100)

  def parse (msg: Array[Byte], lim: Int): FlightPosSeq = {
    def parseState(): Unit = {
      ensureNextIsArrayStart()
      val icao = readQuotedValue().intern
      val cs = readQuotedValue().intern
      skipInCurrentLevel(1)
      val timePos = readUnQuotedValue().toLong
      skipInCurrentLevel(1)
      val lon = readUnQuotedValue().toDoubleOrNaN
      val lat = readUnQuotedValue().toDoubleOrNaN
      val alt = readUnQuotedValue().toDoubleOrNaN
      skipInCurrentLevel(1)
      val spd = readUnQuotedValue().toDoubleOrNaN
      val hdg = readUnQuotedValue().toDoubleOrNaN
      val vr = readUnQuotedValue().toDoubleOrNaN
      skipToEndOfCurrentLevel()
      ensureNextIsArrayEnd()

      //println(f" $n%2d:  '$icao%10s', '$cs%10s', $timePos%12d,  ($lat%10.5f, $lon%10.5f), $alt%7.2f, $spd%5.2f, $hdg%3.0f, $vr%5.2f")

      if (cs.nonEmpty && !lat.isNaN && !lon.isNaN && !alt.isNaN && !hdg.isNaN && !spd.isNaN){
        elements += new FlightPos(
          icao,cs.trim,GeoPosition.fromDegreesAndMeters(lat,lon,alt),MetersPerSecond(spd),Degrees(hdg),
          MetersPerSecond(vr),DateTime.ofEpochMillis(timePos*1000)
        )
      }
    }

    clearElements

    if (initialize(msg,lim)){
      ensureNextIsObjectStart()
      val t = readUnQuotedMember(_time_).toLong
      foreachInNextArrayMember(_states_){
        parseState()
      }
      ensureNextIsObjectEnd()
    }

    elements
  }

  def parse (msg: Array[Byte]): Seq[FlightPos] = parse(msg,msg.length)
}

/**
  * a configurable translator that is based on OpenSkyParser
  */
class OpenSky2FlightPos (val config: Config=NoConfig) extends OpenSkyParser with ConfigurableTranslator {

  // this is lazy since it is only used if we have to translate strings
  lazy val bb = new Utf8Buffer(config.getIntOrElse("buffer-size", 8192))


  override def translate (src: Any): Option[Any] = {
    def result (list: Seq[FlightPos]): Option[Seq[FlightPos]] = if (list.isEmpty) None else Some(list)

    src match {
      case msg: Array[Byte] =>
        result(parse(msg,msg.length))
      case s: String =>
        bb.encode(s)
        result(parse(bb.data,bb.len))
      case _ => None
    }
  }
}
