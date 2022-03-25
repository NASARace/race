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
package gov.nasa.race.track

import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveReader, ArchiveWriter}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream, createOutputStream}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{CsvPullParser, JsonPullParser, JsonSerializable, JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.SingleTypeAkkaSerializer
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject._
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Speed.MetersPerSecond
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}
import gov.nasa.race.{Failure, OrgObject, ResultValue, SuccessValue}

import java.io.{InputStream, OutputStream, PrintStream}

object GpsGroundPos {
  val HDOP = asc("hdop")
  val VDOP = asc("vdop")
  val PDOP = asc("pdop")

  val UNDEFINED_DOP = 0
  val UNDEFINED_ROLE = 0
  val UNDEFINED_ORG = 0

  @inline def ggpResult(id: String, cs: String, lat: Angle, lon: Angle, alt: Length, date: DateTime, role: Int, org: Int,
                spd: Speed, hdg: Angle, vr: Speed, hdop: Int, vdop: Int, pdop: Int, status: Int): ResultValue[GpsGroundPos] = {
    if (id == null && cs == null) return Failure("no ID or LABEL")
    val _cs = if (cs == null) id else cs
    val _id = if (id == null) cs else id
    if (lat.isUndefined || lon.isUndefined) return Failure("no LAT/LON")
    if (date.isUndefined) return Failure("no DATE")

    SuccessValue(new GpsGroundPos(_id,_cs,GeoPosition(lat,lon,alt),date,role,org,spd,hdg,vr,hdop,vdop,pdop,status))
  }
}
import gov.nasa.race.track.GpsGroundPos._

/**
  * a moving GPS track that can have a role/org
  *
  * note that not all fields might be set (according to sender capabilities) so check for undefined values
  *
  * DOP (dilution of precision) valuess:
  *   1      Ideal - Highest possible confidence level to be used for applications demanding the highest possible precision at all times.
  *   1-2    Excellent - positional measurements are considered accurate enough to meet all but the most sensitive applications.
  *   2-5    Good - marks the minimum appropriate for making accurate decisions. Positional measurements could be used to make reliable in-route navigation suggestions to the user.
  *   5-10   Moderate - positional measurements could be used for calculations, but the fix quality could still be improved. A more open view of the sky is recommended.
  *   10-20  Fair - positional measurements should be discarded or used only to indicate a very rough estimate of the current location.
  *   >20    Poor - measurements are inaccurate by as much as 300 meters with a 6-meter accurate device (50 DOP × 6 meters) and should be discarded.
  */
class GpsGroundPos (
                     val id: String,
                     val cs: String,
                     val position: GeoPosition,
                     val date: DateTime,

                     //--- optional fields (sensor/source specific)

                     val role: Int = GpsGroundPos.UNDEFINED_ROLE,
                     val org: Int = GpsGroundPos.UNDEFINED_ORG,

                     val speed: Speed = Speed.UndefinedSpeed,
                     val heading: Angle = Angle.UndefinedAngle,
                     val vr: Speed = Speed.UndefinedSpeed,

                     val hdop: Int = GpsGroundPos.UNDEFINED_DOP,
                     val vdop: Int = GpsGroundPos.UNDEFINED_DOP,
                     val pdop: Int = GpsGroundPos.UNDEFINED_DOP,

                     val status: Int = 0
                   ) extends TrackedObject with OrgObject with Moving3dObject with JsonSerializable {

  override def toString(): String = s"GpsGroundPos($id,$cs,$position,$role,$org,,0x${status.toHexString},$date)"

  def serializeTo (writer: JsonWriter): Unit = {
    writer
      .beginObject
      .writeStringMember(ID, id)
      .writeStringMember(LABEL, cs)
      .writeDoubleMember(LAT, position.latDeg)
      .writeDoubleMember(LON, position.lonDeg)
      .writeDoubleMember(ALT, position.altMeters)
      .writeDateTimeMember(DATE, date)

      .writeIntMember(ROLE, role)
      .writeIntMember(ORG, org)

      .writeDoubleMember(HDG, heading.toDegrees)
      .writeDoubleMember(SPD, speed.toMetersPerSecond)
      .writeDoubleMember(VR, vr.toMetersPerSecond)

      .writeIntMember(HDOP, hdop)
      .writeIntMember(VDOP, vdop)
      .writeIntMember(PDOP, pdop)

      .writeIntMember(STATUS, status)
      .endObject
  }

  def serializeFormattedTo (writer: JsonWriter): Unit = {
    writer
      .beginObject
      .writeStringMember(ID, id)
      .writeStringMember(LABEL, cs)
      .writeDoubleMember(LAT, position.latDeg, FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg, FMT_3_5)
      .writeDoubleMember(ALT, position.altMeters, FMT_6)
      .writeDateTimeMember(DATE, date)

      .writeIntMember(ROLE, role)
      .writeIntMember(ORG, org)

      .writeDoubleMember(HDG, heading.toDegrees, FMT_3)
      .writeDoubleMember(SPD, speed.toMetersPerSecond, FMT_3_2)
      .writeDoubleMember(VR, vr.toMetersPerSecond, FMT_3_2)

      .writeIntMember(HDOP, hdop)
      .writeIntMember(VDOP, vdop)
      .writeIntMember(PDOP, pdop)

      .writeIntMember(STATUS, status)
      .endObject
  }

  def serializeCsvTo (ps: PrintStream): Unit = {

    ps.print(id); ps.print(',')
    ps.print(cs); ps.print(',')
    ps.print(position.latDeg); ps.print(',')
    ps.print(position.lonDeg); ps.print(',')
    if (position.altitude.isDefined) ps.print(position.altMeters); ps.print(',')
    ps.print(date.toEpochMillis); ps.print(',')

    if (role != UNDEFINED_ROLE) ps.print(role); ps.print(',')
    if (org != UNDEFINED_ORG) ps.println(org); ; ps.print(',')

    if (heading.isDefined) ps.print(heading.toDegrees); ps.print(',')
    if (speed.isDefined) ps.print(speed.toMetersPerSecond); ps.print(',')
    if (vr.isDefined) ps.print(vr.toMetersPerSecond); ps.print(',')

    if (hdop != UNDEFINED_DOP) ps.print(hdop); ps.print(',')
    if (vdop != UNDEFINED_DOP) ps.print(vdop); ps.print(',')
    if (pdop != UNDEFINED_DOP) ps.print(pdop); ps.print(',')

    if (status != UndefinedStatus) ps.print(status)
  }
}

//--- Akka serialization support (for remote actors)

class GpsGroundPosSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[GpsGroundPos](system) {
  override val initCapacity: Int = 64

  def serialize (gps: GpsGroundPos): Unit = {
    writeUTF(gps.id)
    writeUTF(gps.cs)
    writeGeoPosition(gps.position)
    writeDateTime(gps.date)

    writeInt(gps.role)
    writeInt(gps.org)

    writeSpeed(gps.speed)
    writeAngle(gps.heading)
    writeSpeed(gps.vr)

    writeInt(gps.hdop)
    writeInt(gps.vdop)
    writeInt(gps.pdop)

    writeInt(gps.status)
  }

  def deserialize(): GpsGroundPos = {
    val id  = readUTF()
    val cs  = readUTF()
    val pos = readGeoPosition()
    val date = readDateTime()

    val role = readInt()
    val org = readInt()

    val spd = readSpeed()
    val hdg = readAngle()
    val vr  = readSpeed()

    val hdop = readInt()
    val vdop = readInt()
    val pdop = readInt()

    val status = readInt()

    new GpsGroundPos(id,cs,pos,date, role,org, spd,hdg,vr, hdop,vdop,pdop, status)
  }
}

//--- archive support

/**
  * archive writer that stores as CSV with pre-pended archive/replay time (which can differ from position time)
  */
class GgpArchiveWriter (val oStream: OutputStream, val pathName: String="<unknown>") extends ArchiveWriter {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  val ps = new PrintStream(oStream)
  override def close = ps.close

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case gps: GpsGroundPos =>
        ps.print(date.toEpochMillis)
        ps.print(',')
        gps.serializeCsvTo(ps)
        ps.println()
        true
    }
  }
}

/**
  * corresponding archive reader
  */
class GgpArchiveReader (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int) extends ArchiveReader with GgpCsvParser {
  val lineBuffer = new LineBuffer(iStream,bufLen)

  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))

  override def hasMoreData: Boolean = {
    iStream.available > 0
  }

  override def readNextEntry: Option[ArchiveEntry] = {
    if (lineBuffer.nextLine()) {
      if (initialize(lineBuffer)){
        val date = DateTime.ofEpochMillis(readNextValue.toLong)
        parseGpsGroundPos match {
          case SuccessValue(gps) => archiveEntry(date,gps)
          case Failure(_) => None
        }
      } else None
    } else None
  }

  override def close: Unit = iStream.close
}

//--- JSON parser

/**
  * JsonPullParser mixin to read GpsGroundPos objects
  */
trait GgpJsonPullParser extends JsonPullParser {

  def parseGpsGroundPos: ResultValue[GpsGroundPos] = {
    var id: String = null
    var cs: String = null
    var lat: Angle = Angle.UndefinedAngle
    var lon: Angle = Angle.UndefinedAngle
    var alt: Length = Length.UndefinedLength
    var date: DateTime = DateTime.UndefinedDateTime
    var role: Int = UNDEFINED_ROLE
    var org: Int = UNDEFINED_DOP
    var spd: Speed = Speed.UndefinedSpeed
    var hdg: Angle = Angle.UndefinedAngle
    var vr: Speed = Speed.UndefinedSpeed
    var hdop: Int = UNDEFINED_DOP
    var vdop: Int = UNDEFINED_DOP
    var pdop: Int = UNDEFINED_DOP
    var status: Int = TrackedObject.UndefinedStatus

    try {
      foreachMemberInCurrentObject {
        case ID => id = quotedValue.intern
        case LABEL => cs = quotedValue.intern
        case LAT => lat = Degrees(unQuotedValue.toDouble)
        case LON => lon = Degrees(unQuotedValue.toDouble)
        case ALT => alt = Meters(unQuotedValue.toDouble)
        case DATE => date = DateTime.ofEpochMillis(unQuotedValue.toLong)
        case ROLE => role = unQuotedValue.toInt
        case ORG => org = unQuotedValue.toInt
        case SPD => spd = MetersPerSecond(unQuotedValue.toDouble)
        case HDG => hdg = Degrees(unQuotedValue.toDouble)
        case VR => vr = MetersPerSecond(unQuotedValue.toDouble)
        case HDOP => hdop = unQuotedValue.toInt
        case VDOP => vdop = unQuotedValue.toInt
        case PDOP => pdop = unQuotedValue.toInt
        case STATUS => status = unQuotedValue.toInt
      }

      ggpResult(id,cs,lat,lon,alt,date,role,org,spd,hdg,vr,hdop,vdop,pdop,status)

    } catch { // basic JSON parse error, likely corrupted data
      case x: Throwable => Failure(x.getMessage)
    }
  }
}

/**
  * class to parse GpsGroundPos lines
  */
trait GgpCsvParser extends Utf8CsvPullParser {

  def parseGpsGroundPos: ResultValue[GpsGroundPos] = {
    def readInternString: String = {
      val v = readNextValue
      if (v.isEmpty) null
      else v.intern
    }

    try {
      val id: String = readInternString
      val cs: String = readInternString
      val lat: Angle = Degrees(readNextValue.toDouble)
      val lon: Angle = Degrees(readNextValue.toDouble)
      val alt: Length = Meters(readNextValue.toDouble)
      val date: DateTime = DateTime.ofEpochMillis(readNextValue.toLong)
      val role: Int = readNextValue.toInt
      val org: Int = readNextValue.toInt
      val spd: Speed = MetersPerSecond(readNextValue.toDouble)
      val hdg: Angle = Degrees(readNextValue.toDouble)
      val vr: Speed = MetersPerSecond(readNextValue.toDouble)
      val hdop: Int = readNextValue.toInt
      val vdop: Int = readNextValue.toInt
      val pdop: Int = readNextValue.toInt
      val status: Int = readNextValue.toInt
      skipToEndOfRecord

      ggpResult(id,cs,lat,lon,alt,date,role,org,spd,hdg,vr,hdop,vdop,pdop,status)

    } catch { // basic CSV parse error, likely corrupted data
      case x: Throwable => Failure(x.getMessage)
    }
  }
}