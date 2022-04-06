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

import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config
import gov.nasa.race.archive._
import gov.nasa.race.common.AssocSeqImpl
import gov.nasa.race.common.ConfigurableStreamCreator._
import gov.nasa.race.core.{AkkaSerializer, SingleTypeAkkaSerializer}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject.TrackProblem
import gov.nasa.race.track.{MutSrcTracks, TrackedObject, Tracked3dObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.{DateTime, _}
import gov.nasa.race.util.InputStreamLineTokenizer

import java.io.{InputStream, OutputStream, PrintStream}


object FlightPos {

  // since FlightPos is not a case class anymore we provide a unapply method for convenience
  // NOTE - don't select on floating point values (position, speed etc.) or date (which is a millisecond epoch)
  def unapply (o: FlightPos): Option[(String,String,GeoPosition,Speed,Angle,Speed,DateTime,Int)] = {
    Some((o.id,o.cs,o.position,o.speed,o.heading,o.vr,o.date,o.status))
  }
}

/**
  * in-flight state consisting of geographic position, altitude, speed and bearing
  * note that we intentionally don't use a case class here so that we can provide structural extensibility
  */
class FlightPos (val id: String,
                 val cs: String,
                 val position: GeoPosition,
                 val speed: Speed,
                 val heading: Angle,
                 val vr: Speed,
                 val date: DateTime,
                 val status: Int = 0
                ) extends TrackedAircraft {

  def this (id:String, pos: GeoPosition, spd: Speed, hdg: Angle, vr: Speed, dtg: DateTime) =
    this(id, TrackedObject.tempCS(id), pos,spd,hdg,vr,dtg)

  def copyWithCS (newCS: String) = new FlightPos(id, newCS, position,speed,heading,vr,date,status)
  def copyWithStatus (newStatus: Int) = new FlightPos(id, cs, position,speed,heading,vr,date,newStatus)

  override def toString = s"FlightPos($id,$cs,$position,${speed.toKnots.toInt}kn,${heading.toNormalizedDegrees.toInt}°,0x${status.toHexString},$date)"

  override def equals (o: Any): Boolean = {
    if (o.getClass == getClass) {
      val other = o.asInstanceOf[FlightPos]
      id == other.id && cs == other.cs &&
        position == other.position &&
        speed == other.speed && heading == other.heading &&
        vr == other.vr && date == other.date && status == other.status
    } else false
  }
}

/**
  * a FlightPos specialization that adds more dynamic vehicle state
  */
class ExtendedFlightPos(id: String,
                        cs: String,
                        position: GeoPosition,
                        speed: Speed,
                        heading: Angle,
                        vr: Speed,
                        date: DateTime,
                        status: Int = 0,
                        //--- additional fields
                        override val pitch: Angle,
                        override val roll: Angle,
                        override val acType: String
                        //.. and possibly more to follow
                       ) extends FlightPos(id, cs, position, speed, heading, vr, date, status) {
  override def toString = s"ExtendedFlightPos($id,$cs,$acType,$position,${speed.toKnots.toInt}kn,${heading.toNormalizedDegrees.toInt}°,${pitch.toDegrees.toInt}°,${roll.toDegrees.toInt}°,0x${status.toHexString},$date)"
  override def copyWithCS (newCS: String) = new ExtendedFlightPos(id, newCS, position,speed,heading,vr,date,status,pitch,roll,acType)
  override def copyWithStatus (newStatus: Int) = new ExtendedFlightPos(id, cs, position,speed,heading,vr,date,newStatus,pitch,roll,acType)

  override def equals (o: Any): Boolean = {
    if (o.getClass == getClass) {
      val other = o.asInstanceOf[ExtendedFlightPos]
      super.equals(other) && pitch == other.pitch && roll == other.roll && acType == other.acType
    } else false
  }
}


//-------------------------------------- supporting codecs
/**
  * a FlightPos archiver that writes/parses FlightPos objects to/from text lines
  */
class FlightPosArchiveWriter (val oStream: OutputStream, val pathName: String="<unknown>") extends ArchiveWriter {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  val ps = new PrintStream (oStream)
  override def close(): Unit = ps.close

  protected def writeFlightPos(fpos: FlightPos): Unit = {
    ps.print(fpos.id); ps.print(',')
    ps.print(fpos.cs); ps.print(',')

    val pos = fpos.position
    ps.print(pos.φ.toDegrees); ps.print(',')
    ps.print(pos.λ.toDegrees); ps.print(',')
    ps.print(pos.altitude.toFeet); ps.print(',')

    ps.print(fpos.speed.toUsMilesPerHour); ps.print(',')
    ps.print(fpos.heading.toDegrees); ps.print(',')
    ps.print(fpos.vr.toFeetPerMinute); ps.print(',')
    ps.print(fpos.date.toEpochMillis);  ps.print(',')
    ps.print(fpos.status)
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case fpos: FlightPos =>
        ps.print(date.toEpochMillis)
        ps.print(',')
        writeFlightPos(fpos)
        ps.println()
        true
      case _ => false
    }
  }
}

class ExtendedFlightPosArchiveWriter (oStream: OutputStream, pathName: String) extends FlightPosArchiveWriter(oStream,pathName) {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  protected def writeExtendedFlightPos (xfpos: ExtendedFlightPos): Unit = {
    writeFlightPos(xfpos); ps.print(',')

    ps.print(xfpos.pitch.toDegrees); ps.print(',')
    ps.print(xfpos.roll.toDegrees); ps.print(',')
    ps.print(xfpos.acType)
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case xfpos: ExtendedFlightPos =>
        ps.print(date.toEpochMillis); ps.print(',')
        writeExtendedFlightPos(xfpos); ps.println()
        true
      case _ => false
    }
  }
}

class FlightPosArchiveReader (val iStream: InputStream, val pathName: String="<unknown>")
                                                 extends ArchiveReader with InputStreamLineTokenizer {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf))

  def hasMoreArchivedData = iStream.available > 0
  def close(): Unit = iStream.close

  override def readNextEntry(): Option[ArchiveEntry] = {
    var fs = getLineFields(iStream)

    if (fs.size == 10) {
      try {
        val recDt = fs.head.toLong; fs = fs.tail
        val flightId = fs.head.intern; fs = fs.tail
        val cs = fs.head.intern; fs = fs.tail
        val phi = fs.head.toDouble; fs = fs.tail
        val lambda = fs.head.toDouble; fs = fs.tail
        val alt = fs.head.toDouble; fs = fs.tail
        val speed = fs.head.toDouble; fs = fs.tail
        val heading = fs.head.toDouble; fs = fs.tail
        val vr = fs.head.toDouble; fs = fs.tail

        val date = getDate(DateTime.ofEpochMillis(fs.head.toLong)); fs = fs.tail  // we might adjust it on-the-fly
        val status = fs.head.toInt

        archiveEntry(date, new FlightPos(flightId, cs, GeoPosition(Degrees(phi), Degrees(lambda), Feet(alt)),
                                      UsMilesPerHour(speed), Degrees(heading), FeetPerMinute(vr), date,status))
      } catch {
        case x: Throwable => None
      }
    } else None
  }
}

class ExtendedFlightPosArchiveReader (iStream: InputStream, pathName: String) extends FlightPosArchiveReader(iStream,pathName) {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf))

  override def readNextEntry(): Option[ArchiveEntry] = {
    var fs = getLineFields(iStream)

    if (fs.size == 13) {
      try {
        // bad - duplicated code, but avoiding temp objects is more important
        val recDt = fs.head.toLong; fs = fs.tail
        val flightId = fs.head.intern; fs = fs.tail
        val cs = fs.head.intern; fs = fs.tail
        val phi = fs.head.toDouble; fs = fs.tail
        val lambda = fs.head.toDouble; fs = fs.tail
        val alt = fs.head.toDouble; fs = fs.tail
        val speed = fs.head.toDouble; fs = fs.tail
        val heading = fs.head.toDouble; fs = fs.tail
        val vr = fs.head.toDouble; fs = fs.tail
        val date = getDate(DateTime.ofEpochMillis(fs.head.toLong)); fs = fs.tail  // we might adjust it on-the-fly
        val status = fs.head.toInt

        val pitch = fs.head.toDouble; fs = fs.tail
        val roll = fs.head.toDouble; fs = fs.tail
        val acType = fs.head.intern

        archiveEntry(date, new ExtendedFlightPos(flightId, cs, GeoPosition(Degrees(phi), Degrees(lambda), Feet(alt)),
                                              UsMilesPerHour(speed), Degrees(heading), FeetPerMinute(vr), date,status,
                                              Degrees(pitch), Degrees(roll), acType))
      } catch {
        case x: Throwable => None
      }
    } else None
  }
}


trait FlightPosChecker {
  // overide the ones you need
  def check (fpos: Tracked3dObject): Option[TrackProblem] = None
  def checkPair (fpos: Tracked3dObject, lastFPos: Tracked3dObject): Option[TrackProblem] = None
}

object EmptyFlightPosChecker extends FlightPosChecker

/**
  * matchable type for FlightPos Seqs
  */
trait FlightPosSeq extends TrackedAircraftSeq[FlightPos]

/**
  * mutable implementation of FlightPosSeq
  */
class MutFlightPosSeqImpl(initSize: Int) extends MutSrcTracks[FlightPos](initSize) with FlightPosSeq

/**
  * immutable FlightPosSeq
  */
class FlightPosSeqImpl(assoc: String, elems: Array[FlightPos]) extends AssocSeqImpl(assoc,elems) with FlightPosSeq


//--- Akka serializer support

trait FlightPosSer extends AkkaSerializer {
  def serializeFlightPos (fpos: FlightPos): Unit = {
    writeUTF(fpos.id)
    writeUTF(fpos.cs)
    writeGeoPosition(fpos.position)
    writeSpeed(fpos.speed)
    writeAngle(fpos.heading)
    writeSpeed(fpos.vr)
    writeDateTime(fpos.date)
    writeInt(fpos.status)
  }

  def deserializeFlightPos (): FlightPos = {
    val id  = readUTF()
    val cs  = readUTF()
    val pos = readGeoPosition()
    val spd = readSpeed()
    val hdg = readAngle()
    val vr  = readSpeed()
    val date = readDateTime()
    val status = readInt()

    new FlightPos(id, cs, pos, spd, hdg, vr, date, status)
  }
}

/**
  * Akka serialization support for FlightPos objects
  * this implementation favors minimizing allocation and absence of 3rd party library dependencies.
  */
class FlightPosSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[FlightPos](system) with FlightPosSer {
  override val initCapacity: Int = 64
  override def serialize (fpos: FlightPos): Unit = serializeFlightPos(fpos)
  override def deserialize (): FlightPos = deserializeFlightPos()
}

class FlightPosSeqSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[FlightPosSeq](system) with FlightPosSer {
  override def serialize(t: FlightPosSeq): Unit = {
    writeUTF(t.assoc)
    writeItems(t)(serializeFlightPos)
  }

  override def deserialize(): FlightPosSeq = {
    val assoc = readUTF()
    val items = readItems(deserializeFlightPos)
    new FlightPosSeqImpl(assoc,items)
  }
}