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
import gov.nasa.race.air.FlightPos
import gov.nasa.race.common.{UTF8Buffer, UTF8JsonPullParser}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Speed._
//import io.circe.{Decoder, HCursor, Json, parser}

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

/**
  * translator for OpenSky JSON query results as documented in https://opensky-network.org/apidoc/rest.html
  *
  * using opensky-net json format as defined in: https://opensky-network.org/apidoc/rest.html
  * {
  *   "time": 1568090280,
  *   "states": [
  *      ["4b1803", "SWR5181 ", "Switzerland", 1568090266, 1568090279, 8.5594, 47.4533, 9197.34,
  *       true, 0, 5, null, null, null, "1021", false, 0], ...
  *    ]
  * }
  */
class OpenSky2FlightPos (val config: Config=NoConfig) extends UTF8JsonPullParser with ConfigurableTranslator {

  lazy val bb = new UTF8Buffer(config.getIntOrElse("buffer-size", 8192))

  val _states_ = Slice("states")
  val _time_ = Slice("time")

  override def translate (src: Any): Option[Any] = {
    src match {
      case msg: Array[Byte] =>
        parse(msg,msg.length)
      case s: String =>
        bb.encode(s)
        parse(bb.data,bb.length)
      case _ => None
    }
  }

  def parse (msg: Array[Byte], lim: Int): Option[Seq[FlightPos]] = {
    val list = new ArrayBuffer[FlightPos](50)

    def parseState: Unit = {
      matchArrayStart
      val icao = readQuotedValue.intern
      val cs = readQuotedValue.intern
      skip(1)
      val timePos = readUnQuotedValue.toLong
      skip(1)
      val lon = readUnQuotedValue.toDoubleOrNaN
      val lat = readUnQuotedValue.toDoubleOrNaN
      val alt = readUnQuotedValue.toDoubleOrNaN
      skip(1)
      val spd = readUnQuotedValue.toDoubleOrNaN
      val hdg = readUnQuotedValue.toDoubleOrNaN
      val vr = readUnQuotedValue.toDoubleOrNaN
      skipToEndOfCurrentLevel
      matchArrayEnd

      //println(f" $n%2d:  '$icao%10s', '$cs%10s', $timePos%12d,  ($lat%10.5f, $lon%10.5f), $alt%7.2f, $spd%5.2f, $hdg%3.0f, $vr%5.2f")

      if (cs.nonEmpty && !lat.isNaN && !lon.isNaN && !alt.isNaN && !hdg.isNaN && !spd.isNaN){
        list += new FlightPos(
          icao,cs.trim,GeoPosition.fromDegreesAndMeters(lat,lon,alt),MetersPerSecond(spd),Degrees(hdg),
          MetersPerSecond(vr),DateTime.ofEpochMillis(timePos*1000)
        )
      }
    }

    if (initialize(msg,lim)){
      matchObjectStart
      val t = readUnQuotedMember(_time_).toLong
      readMemberArray(_states_){
        parseState
      }
      matchObjectEnd
    }

    if (list.nonEmpty) Some(list) else None
  }


/*
  implicit val decoder: Decoder[FlightPos] = (hCursor: HCursor) => {
    for {
      icao24 <- hCursor.downArray.as[String]
      cs <- hCursor.downN(1).as[String]
      //origin <- hCursor.downN(2).as[String]
      timePos <- hCursor.downN(3).as[Long]
      //lastContact <- hCursor.downN(4).as[Long]
      lon <- hCursor.downN(5).as[Float]
      lat <- hCursor.downN(6).as[Float]
      alt <- hCursor.downN(7).as[Float]
      //onGround <- hCursor.downN(8).as[Boolean]
      speed <- hCursor.downN(9).as[Float]
      trueTrack <- hCursor.downN(10).as[Float]
      vr <- hCursor.downN(11).as[Float]
      //geoAlt <- hCursor.downN(13).as[Float]
      //squawk <- hCursor.downN(14).as[String]
      //spi <- hCursor.downN(15).as[Boolean]
      //posSrc <- hCursor.downN(16).as[Int]
    } yield {
      new FlightPos(
        icao24,cs.trim,GeoPosition.fromDegreesAndMeters(lat,lon,alt),MetersPerSecond(speed),Degrees(trueTrack),
        MetersPerSecond(vr),DateTime.ofEpochMillis(timePos*1000)
      )
    }
  }

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String => translateJson(s)
      case _ => None
    }
  }

  def validateFpos(fpos: FlightPos): Boolean = {
    val cs = fpos.cs
    if (cs.isEmpty || !cs.charAt(0).isLetter) return false

    val pos = fpos.position
    if (pos.lat.isUndefined || pos.lon.isUndefined || pos.altitude.isUndefined) {
      //println(s"@@@ incomplete pos: $fpos")
      return false
    }

    true
  }

  def translateJson (input: String): Option[Seq[FlightPos]] = {
    val json: Json = parser.parse(input).getOrElse(Json.Null)
    val data: Option[Json] = json.hcursor.downField("states").focus

    data match {
      case Some(list) =>
        list.hcursor.as[List[FlightPos]] match {
          case Right(flist) => Some(flist.filter(validateFpos))
          case Left(err) => println(err); None
        }
      case None => None
    }
  }
  */
}
