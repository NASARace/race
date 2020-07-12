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
package gov.nasa.race.air

import java.io.File

import gov.nasa.race.common.{ASCII8Internalizer, ConstAsciiSlice, UTF8JsonPullParser}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.test._
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.DateTime
import gov.nasa.race.uom.Speed.MetersPerSecond
import gov.nasa.race.util.FileUtils
import io.circe.{Json, parser}

/**
  * benchmark for JsonPullParser
  *
  * using opensky-net json format:
  * {
  *   "time": 1568090280,
  *   "states": [
  *      ["4b1803", "SWR5181 ", "Switzerland", 1568090266, 1568090279, 8.5594, 47.4533, 9197.34,
  *       true, 0, 5, null, null, null, "1021", false, 0], ...
  *    ]
  * }
  */
object OpenSkyBenchmark {

  var nRounds = 10000

  def main (args: Array[String]): Unit = {

    if (args.nonEmpty) {
      nRounds = args(0).toInt
    }

    val msg = FileUtils.fileContentsAsBytes(new File("src/test/resources/opensky.json")).get
    println(s"byte size of input message: ${msg.length}")

    runCirce(msg)
    runJsonPullParser(msg)
  }

  def prompt (msg: String): Unit = {
    println(msg)
    System.in.read
  }

  def measure (rounds: Int)(f: =>Unit): Unit = {
    gc

    var j = rounds
    val g0 = gcCount(0)
    val gt0 = gcMillis(0)
    val m0 = usedHeapMemory
    val t0 = System.nanoTime

    while (j > 0) {
      f
      j -= 1
    }

    val t1 = System.nanoTime
    val m1 = usedHeapMemory
    val gt1 = gcMillis(0)
    val g1 = gcCount(0)

    println(s"  ${(t1 - t0)/1000000} msec")
    println(s"  ${(m1 - m0)/1024} kB")
    println(s"  ${g1 - g0} gc cycles, ${gt1 - gt0} msec")
  }

  //--- JsonPullParser based parsing

  def runJsonPullParser (msg: Array[Byte]): Unit = {
    val _states_ = ConstAsciiSlice("states")
    val _time_ = ConstAsciiSlice("time")
    println(s"--- running JsonPullParser")

    var n = 0
    val p = new UTF8JsonPullParser

    def readState: FlightPos = {
      p.ensureNextIsArrayStart()
      val icao = p.readQuotedValue().intern
      val cs = p.readQuotedValue().intern
      p.skipInCurrentLevel(1)
      val timePos = p.readUnQuotedValue().toLong
      p.skipInCurrentLevel(1)
      val lon = p.readUnQuotedValue().toDouble
      val lat = p.readUnQuotedValue().toDouble
      val alt = p.readUnQuotedValue().toDoubleOrNaN
      p.skipInCurrentLevel(1)
      val spd = p.readUnQuotedValue().toDouble
      val hdg = p.readUnQuotedValue().toDouble
      val vr = p.readUnQuotedValue().toDoubleOrNaN
      p.skipToEndOfCurrentLevel()
      p.ensureNextIsArrayEnd()

      n += 1
      //println(f" $n%2d:  '$icao%10s', '$cs%10s', $timePos%12d,  ($lat%10.5f, $lon%10.5f), $alt%7.2f, $spd%5.2f, $hdg%3.0f, $vr%5.2f")
      new FlightPos(
        icao,cs.trim,GeoPosition.fromDegreesAndMeters(lat,lon,alt),MetersPerSecond(spd),Degrees(hdg),
        MetersPerSecond(vr),DateTime.ofEpochMillis(timePos*1000)
      )
    }

    measure(nRounds){
      p.initialize(msg)

      p.ensureNextIsObjectStart()
      val t = p.readUnQuotedMember(_time_).toLong
      p.foreachInNextArrayMember(_states_){
        readState
      }
      p.ensureNextIsObjectEnd()
    }
    println(s"  done - $n items parsed")
  }


  //--- circe based parsing

  import io.circe.{Decoder, HCursor}

  def runCirce (msg: Array[Byte]): Unit = {

    println("--- running Circe parser")

    implicit val decoder: Decoder[FlightPos] = (hCursor: HCursor) => {
      for {
        icao <- hCursor.downArray.as[String]
        cs <- hCursor.downN(1).as[String]
        //origin <- hCursor.downN(2).as[String]
        timePos <- hCursor.downN(3).as[Long]
        //lastContact <- hCursor.downN(4).as[Long]
        lon <- hCursor.downN(5).as[Float]
        lat <- hCursor.downN(6).as[Float]
        alt <- hCursor.downN(7).as[Float]
        //onGround <- hCursor.downN(8).as[Boolean]
        spd <- hCursor.downN(9).as[Float]
        hdg <- hCursor.downN(10).as[Float]
        vr <- hCursor.downN(11).as[Float]
        //geoAlt <- hCursor.downN(13).as[Float]
        //squawk <- hCursor.downN(14).as[String]
        //spi <- hCursor.downN(15).as[Boolean]
        //posSrc <- hCursor.downN(16).as[Int]
      } yield {
        //println(f" $n%2d:  '$icao%10s', '$cs%10s', $timePos%12d,  ($lat%10.5f, $lon%10.5f), $alt%7.2f, $spd%5.2f, $hdg%3.0f, $vr%5.2f")
        new FlightPos(
          ASCII8Internalizer.get(icao),ASCII8Internalizer.get(cs.trim),GeoPosition.fromDegreesAndMeters(lat,lon,alt),MetersPerSecond(spd),Degrees(hdg),
          MetersPerSecond(vr),DateTime.ofEpochMillis(timePos*1000)
        )
      }
    }

    val input = new String(msg) // circe doesn't parse byte arrays?
    var n = 0

    measure(nRounds) {
      val json: Json = parser.parse(input).getOrElse(Json.Null)
      val data: Option[Json] = json.hcursor.downField("states").focus

      data match {
        case Some(list) =>
          list.hcursor.as[List[FlightPos]] match {
            case Right(flist) => n += flist.size
            case Left(err) => println(err)
          }
        case None => None
      }
    }
    println(s"  done - $n items parsed")
  }
}
