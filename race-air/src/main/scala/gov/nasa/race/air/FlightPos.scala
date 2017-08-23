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

import java.io.{InputStream, OutputStream, PrintStream}

import com.github.nscala_time.time.Imports._
import gov.nasa.race.archive._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import gov.nasa.race.util.InputStreamLineTokenizer
import gov.nasa.race.{Dated, IdentifiableObject}
import scodec.bits.BitVector
import scodec.codecs._


object FlightPos {
  def tempCS (flightId: String) = "?" + flightId
  def isTempCS (cs: String) = cs.charAt(0) == '?'

  // c/s changes only happen rarely, and if they do we want to preserve the changed value
  // for all downstream actors so we don't use a fixed field for it
  case class ChangedCS(oldCS: String)
}

/**
  * base type for in-flight related messages
  */
trait FlightMessage {
  def cs: String
}

/**
  * in-flight state consisting of geographic position, altitude, speed and bearing
  */
case class FlightPos (val id: String,
                 val cs: String,
                 val position: LatLonPos,
                 val altitude: Length,
                 val speed: Speed,
                 val heading: Angle,
                 val date: DateTime) extends TrackedAircraft with FlightMessage {

  def this (id:String, pos: LatLonPos, alt: Length,spd: Speed,hdg: Angle, dtg: DateTime) =
    this(id, FlightPos.tempCS(id), pos,alt,spd,hdg,dtg)

  def hasTempCS = FlightPos.isTempCS(cs)
  def tempCS = if (hasTempCS) cs else FlightPos.tempCS(id)
  def getOldCS: Option[String] = amendments.find(_.isInstanceOf[FlightPos.ChangedCS]).map(_.asInstanceOf[FlightPos.ChangedCS].oldCS)
  def copyWithCS (newCS: String) = new FlightPos(id, newCS, position, altitude,speed,heading,date)

  override def toString = s"FlightPos($id,$cs,$position,${altitude.toFeet.toInt}ft,${speed.toKnots.toInt}kn,${heading.toNormalizedDegrees.toInt}°,$date)"

}

trait FlightTerminationMessage extends FlightMessage

case class FlightCompleted (id: String,
                            cs: String,
                            arrivalPoint: String,
                            date: DateTime) extends Dated with IdentifiableObject with FlightTerminationMessage

case class FlightDropped (id: String,
                          cs: String,
                          date: DateTime) extends Dated with IdentifiableObject with FlightTerminationMessage

case class FlightCsChanged (id: String,
                            cs: String,
                            oldCS: String,
                            date: DateTime) extends Dated with IdentifiableObject

//-------------------------------------- supporting codecs
/**
  * a FlightPos archiver that writes/parses FlightPos objects to/from text lines
  */
class FlightPosArchiveWriter (val ostream: OutputStream) extends ArchiveWriter {
  val ps = new PrintStream (ostream)

  override def close = ps.close

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case fpos: FlightPos =>
        ps.print(date.getMillis); ps.print(',')
        ps.print(fpos.id); ps.print(',')
        ps.print(fpos.cs); ps.print(',')
        ps.print(fpos.position.φ.toDegrees); ps.print(',')
        ps.print(fpos.position.λ.toDegrees); ps.print(',')
        ps.print(fpos.altitude.toFeet); ps.print(',')
        ps.print(fpos.speed.toUsMilesPerHour); ps.print(',')
        ps.print(fpos.heading.toDegrees); ps.print(',')
        ps.print(fpos.date.getMillis); ps.println()
        true
      case _ => false
    }
  }
}

class FlightPosArchiveReader (val istream: InputStream) extends StreamArchiveReader
  with InputStreamLineTokenizer {

  override def readNextEntry: Option[ArchiveEntry] = {
    var fs = getLineFields(istream)

    if (fs.size == 9) {
      try {
        val recDt = fs.head.toLong; fs = fs.tail
        val flightId = fs.head; fs = fs.tail
        val cs = fs.head; fs = fs.tail
        val phi = fs.head.toDouble; fs = fs.tail
        val lambda = fs.head.toDouble; fs = fs.tail
        val alt = fs.head.toDouble; fs = fs.tail
        val speed = fs.head.toDouble; fs = fs.tail
        val heading = fs.head.toDouble; fs = fs.tail
        val dt = fs.head.toLong

        val date = getDate(dt)  // we might adjust it on-the-fly
        someEntry(date, new FlightPos(flightId, cs, LatLonPos(Degrees(phi), Degrees(lambda)),
                                          Feet(alt), UsMilesPerHour(speed), Degrees(heading), date))
      } catch {
        case x: Throwable => None
      }
    } else None
  }
}

object BinaryFlightPos {
  // we could also use scodecs HList-based codec generator, but the purpose
  // here is to show explicit construction of binary codecs. This still assumes big endian though

  // of course this could also be done directly with a java.nio.ByteBuffer, but that wouldn't
  // have the potential for automatic codec generation and would be much more code

  // <2do> this should use scodec streams instead of Input/OutputStreams

  val fposCodec =
    int64 ~ // recording date in millis
      utf8_32 ~ // flightId
      utf8_32 ~ // cs
      double ~ double ~ // pos phi/lambda in degrees
      double ~ // altitude in feet
      double ~ // speed in UsMilesPerHour
      double ~ // heading in degrees
      int64 // fpos DateTime in millis
}

/**
  * example for a binary FightPos archive reader/writer using scodec
  */
class BinaryFlightPosArchiveWriter (val ostream: OutputStream) extends FramedArchiveWriter {

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case fpos: FlightPos =>
        val bytes = BinaryFlightPos.fposCodec.encode(
          date.getMillis ~
            fpos.id ~
            fpos.cs ~
            fpos.position.φ.toDegrees ~ fpos.position.λ.toDegrees ~
            fpos.altitude.toFeet ~
            fpos.speed.toUsMilesPerHour ~
            fpos.heading.toDegrees ~
            fpos.date.getMillis
        ).require.toByteArray

        writeFrameSize(bytes.length)
        writeFrame(bytes)
        true
      case _ => false
    }
  }
}

class BinaryFlightPosArchiveReader (val istream: InputStream) extends FramedArchiveReader {

  override def readNextEntry: Option[ArchiveEntry] = {
    try {
      val nBytes = readFrameSize
      val bytes = readFrame(nBytes)

      val bitvec = BitVector(bytes)
      val (recDt ~ flightId ~ cs ~ phi ~ lambda ~ alt ~ speed ~ heading ~ dt) =
        BinaryFlightPos.fposCodec.decode(bitvec).require.value

      val date = getDate(recDt)
      someEntry( date, new FlightPos(flightId, cs, LatLonPos(Degrees(phi), Degrees(lambda)),
                                         Feet(alt), UsMilesPerHour(speed), Degrees(heading), date))
    } catch {
      case x: Throwable => None
    }
  }
}

case class FlightPosProblem(fpos: FlightPos, lastFpos: FlightPos, problem: String)

trait FlightPosChecker {
  // overide the ones you need
  def check (fpos: FlightPos): Option[FlightPosProblem] = None
  def checkPair (fpos: FlightPos, lastFPos: FlightPos): Option[FlightPosProblem] = None
}

object EmptyFlightPosChecker extends FlightPosChecker