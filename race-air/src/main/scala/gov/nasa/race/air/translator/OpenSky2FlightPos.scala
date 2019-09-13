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
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Speed._
import io.circe.{Decoder, HCursor, Json, parser}

/**
  * translator for OpenSky JSON query results as documented in https://opensky-network.org/apidoc/rest.html
  *
  * TODO - this needs our own JsonPullParser to save resources
  */
class OpenSky2FlightPos (val config: Config=NoConfig) extends ConfigurableTranslator {

  implicit val decoder: Decoder[FlightPos] = (hCursor: HCursor) => {
    for {
      icao24 <- hCursor.downArray.as[String]
      csRaw <- hCursor.downN(1).as[String]
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
      val cs = if (csRaw.nonEmpty && csRaw.charAt(0).isLetter) csRaw.trim else "?"
      new FlightPos(
        icao24,cs,GeoPosition.fromDegreesAndMeters(lat,lon,alt),MetersPerSecond(speed),Degrees(trueTrack),
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

  def translateJson (input: String): Option[Seq[FlightPos]] = {
    val json: Json = parser.parse(input).getOrElse(Json.Null)
    val data: Option[Json] = json.hcursor.downField("states").focus

    data match {
      case Some(list) =>
        list.hcursor.as[List[FlightPos]] match {
          case Right(flist) => Some(flist.filter(_.cs != "?"))
          case Left(err) => println(err); None
        }
      case None => None
    }
  }
}
