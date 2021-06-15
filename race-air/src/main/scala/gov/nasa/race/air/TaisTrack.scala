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

import java.io.{OutputStream, PrintStream}
import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveWriter, CSVArchiveWriter}
import gov.nasa.race.common._
import gov.nasa.race.common.CSVInputStream
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createOutputStream}
import gov.nasa.race.core.{AkkaSerializer, SingleTypeAkkaSerializer}
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}

import scala.collection.mutable.ArrayBuffer

object TaisTrack {
  // status bit flags for TATrack attrs (should be >0xffff)

  final val PseudoFlag     = 0x10000
  final val AdsbFlag       = 0x20000
  final val CoastingFlag   = 0x40000

  object Status extends Enumeration {
    type Status = Value
    val Active,Coasting,Drop,Undefined = Value
  }
}

/**
  * a specialized TrackedAircraft that represents tracks from TAIS/STARS messages
  */
case class TaisTrack(id: String,
                     cs: String,
                     position: GeoPosition,
                     heading: Angle,
                     speed: Speed,
                     vr: Speed,
                     date: DateTime,
                     status: Int,

                     src: String,
                     trackNum: Int,
                     xyPos: XYPos,
                     beaconCode: String,
                     flightPlan: Option[FlightPlan]
                  ) extends TrackedAircraft {
  import TaisTrack._

  private var _trackNum: Int = -1


  override def toString = {
    f"TATrack($src,$id,$cs,0x${status.toHexString},$position,${heading.toDegrees}%.0f°,${speed.toKnots}%.1fkn,${vr.toFeetPerMinute}%.0f, $date, $flightPlan)"
  }

  override def source: Option[String] = Some(src)

  def isPseudo = (status & PseudoFlag) != 0
  def isAdsb = (status & AdsbFlag) != 0

  def hasFlightPlan = flightPlan.isDefined
}


/**
  * an ArchiveWriter for a collection of TATracks that were received/should be replayed together
  * (normally corresponding to a TATrackAndFlightPlan XML message)
  */
class TaisTrackBatchWriter(val oStream: OutputStream, val pathName: String="<unknown>") extends CSVArchiveWriter {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))


  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case t: TaisTrack => writeTrack(t)
      case it: Iterable[_] => writeTracks(date,it)
      case _ => false
    }
  }

  def writeHeader (date: DateTime, src: String, nTracks: Int): Unit = {
    printFieldSep // start with empty field to allow parsing without object allocation
    printString("---")
    printString(src)
    printDateTimeAsEpochMillis(date)
    printInt(nTracks,recSep)
  }

  def writeTrack (t: TaisTrack): Boolean = {
    printString(t.id)
    printString(t.cs)  // TODO: add optional obfuscation

    printDegrees_5(t.position.lat)
    printDegrees_5(t.position.lon)
    printMeters_1(t.position.altitude)

    printDegrees_0(t.heading)
    printMetersPerSecond_1(t.speed)
    printMetersPerSecond_1(t.vr)

    printDateTimeAsEpochMillis(t.date)
    printInt(t.status)

    //printString(t.src) // the source is stored in the batch header
    printInt(t.trackNum)

    printMeters_1(t.xyPos.x)
    printMeters_1(t.xyPos.y)

    printString(t.beaconCode, recSep)
    // TODO: add flightPlan

    true
  }

  def writeTracks (date: DateTime, it: Iterable[_]): Boolean = {
    var src: String = null
    var res = false

    it.foreach { obj =>
      obj match {
        case t: TaisTrack =>
          if (src == null) {
            src = t.src
            writeHeader(date,src,it.size)
          }
          if (t.src == src) {
            res |= writeTrack(t)
          } // if this is a different source we skip
        case _ => // ignore
      }
    }
    res
  }
}

class TaisTrackCSVReader(in: CSVInputStream) {
  import gov.nasa.race.uom.Angle._
  import gov.nasa.race.uom.Length._
  import gov.nasa.race.uom.Speed._

  def read (src: String): TaisTrack = {
    val id = in.readString
    val cs = in.readString
    val lat = Degrees(in.readDouble)
    val lon = Degrees(in.readDouble)
    val alt = Meters(in.readDouble)
    val hdg = Degrees(in.readDouble)
    val spd = MetersPerSecond(in.readDouble)
    val vr = MetersPerSecond(in.readDouble)
    val date = DateTime.ofEpochMillis(in.readLong)
    val status = in.readInt
    val trackNum = in.readInt
    // we use the batch src
    val x = Meters(in.readDouble)
    val y = Meters(in.readDouble)
    val beacon = in.readOptionalString
    // TODO: add flightPlan
    assert(in.wasEndOfRecord)

    new TaisTrack(id,cs,GeoPosition(lat,lon,alt),hdg,spd,vr,date,status,src,trackNum,XYPos(x,y),beacon,None)
  }
}

/**
  * a matchable collection type of TATrack objects reported by the same TRACON
  */
trait TaisTracks extends TrackedAircraftSeq[TaisTrack] {
  @inline final def traconId: String = assoc
}

object TaisTracks {
  val empty: TaisTracks = new ArrayBuffer[TaisTrack](0) with TaisTracks {
    override def assoc: String = ""
  }
}

class TaisTracksImpl(assoc: String, elems: Array[TaisTrack]) extends AssocSeqImpl(assoc,elems) with TaisTracks


//--- serializer support

trait TaisTrackSer extends AkkaSerializer {
  def serializeTaisTrack (t: TaisTrack): Unit = {
    writeUTF(t.id)
    writeUTF(t.cs)
    writeGeoPosition(t.position)
    writeSpeed(t.speed)
    writeAngle(t.heading)
    writeSpeed(t.vr)
    writeDateTime(t.date)
    writeInt(t.status)

    writeUTF(t.src)
    writeInt(t.trackNum)
    writeXYPos(t.xyPos)
    writeUTF(t.beaconCode)
    // TODO - still need the optional FlightPlan
  }

  def deserializeTaisTrack (): TaisTrack = {
    val id  = readUTF()
    val cs  = readUTF()
    val pos = readGeoPosition()
    val spd = readSpeed()
    val hdg = readAngle()
    val vr  = readSpeed()
    val date = readDateTime()
    val status = readInt()

    val src = readUTF()
    val trackNum = readInt()
    val xyPos = readXYPos()
    val beaconCode = readUTF()

    TaisTrack(id,cs,pos,hdg,spd,vr,date,status,src,trackNum,xyPos,beaconCode, None)
  }
}

class TaisTrackSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[TaisTrack](system) with TaisTrackSer {
  override def serialize(t: TaisTrack): Unit = serializeTaisTrack(t)
  override def deserialize(): TaisTrack = deserializeTaisTrack()
}

class TaisTracksSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[TaisTracks](system) with TaisTrackSer {
  override def serialize(t: TaisTracks): Unit = {
    writeUTF(t.assoc)
    writeItems(t)(serializeTaisTrack)
  }

  override def deserialize(): TaisTracks = {
    val assoc = readUTF()
    val tracks = readItems(deserializeTaisTrack)
    new TaisTracksImpl(assoc,tracks)
  }
}

