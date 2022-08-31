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

import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader, ArchiveWriter, StreamArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream, createOutputStream}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{FMT_3, FMT_3_2, FMT_3_5, FMT_6, JsonPullParser, JsonSerializable, JsonWriter, LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.SingleTypeAkkaSerializer
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject._
import gov.nasa.race.track.{Moving3dObject, TrackedObject}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Speed.{MetersPerSecond, UndefinedSpeed}
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}
import gov.nasa.race.{Failure, OrgObject, ResultValue, SuccessValue}

import java.io.{InputStream, OutputStream, PrintStream}

object GpsPos {
  val ACC  = asc("acc")
  val DIST = asc("dist")
  val HDOP = asc("hdop")
  val VDOP = asc("vdop")
  val PDOP = asc("pdop")

  val UndefinedDop = 0
  val UndefinedRole = 0
  val UndefinedOrg = 0

  @inline def gpsPosResult(id: String, date: DateTime, lat: Angle, lon: Angle, alt: Length, accuracy: Length,
                        hdg: Angle, spd: Speed, dist: Length, org: Int, role: Int, status: Int): ResultValue[GpsPos] = {
    if (id == null || id.isEmpty) return Failure("no ID")
    if (lat.isUndefined || lon.isUndefined) return Failure("no LAT/LON")
    if (date.isUndefined) return Failure("no DATE")

    SuccessValue(new GpsPos(id,date, GeoPosition(lat,lon,alt),accuracy, hdg,spd,dist, org,role,status))
  }
}
import gov.nasa.race.earth.GpsPos._

/**
  * a moving GPS track that can have a role/org
  *
  * note that not all fields might be set (according to sender capabilities) so check for undefined values
  * note also that we don't use a case class here since we might need to extend this class
  *
  * DOP (dilution of precision) valuess:
  *   1      Ideal - Highest possible confidence level to be used for applications demanding the highest possible precision at all times.
  *   1-2    Excellent - positional measurements are considered accurate enough to meet all but the most sensitive applications.
  *   2-5    Good - marks the minimum appropriate for making accurate decisions. Positional measurements could be used to make reliable in-route navigation suggestions to the user.
  *   5-10   Moderate - positional measurements could be used for calculations, but the fix quality could still be improved. A more open view of the sky is recommended.
  *   10-20  Fair - positional measurements should be discarded or used only to indicate a very rough estimate of the current location.
  *   >20    Poor - measurements are inaccurate by as much as 300 meters with a 6-meter accurate device (50 DOP × 6 meters) and should be discarded.
  */
class GpsPos(
              val id: String,
              val date: DateTime,

              val position: GeoPosition,
              val accuracy: Length = Length.UndefinedLength, // error diameter

              val heading: Angle = Angle.UndefinedAngle,
              val speed: Speed = Speed.UndefinedSpeed,
              val distance: Length = Length.UndefinedLength,

              val org: Int = GpsPos.UndefinedOrg,
              val role: Int = GpsPos.UndefinedRole,
              val status: Int = UndefinedStatus
            ) extends TrackedObject with OrgObject with Moving3dObject with JsonSerializable {

  // type specific overrides
  def cs: String = id  // we don't have a separate c/s
  def vr: Speed = UndefinedSpeed // we are on the ground
  override def toString(): String = f"""GpsGroundPos("$id",$date,${position.toGenericString3D},𝚫=${accuracy.toMeters}%.1fm,↖︎=${heading.toDegrees}%.0f°,d=${distance.toMeters}%.0fm,$org,$role,0x${status.toHexString})"""
  override def displayAttrs: Int = TrackedObject.DspGroundFlag

  // NOTE - this needs to be overridden for derived classes
  def copy (id: String=id, date: DateTime = date,
            position: GeoPosition = position, accuracy: Length = accuracy,
            heading: Angle = heading, speed: Speed = speed, distance: Length = distance,
            org: Int = org, role: Int = role, status: Int = status): GpsPos = {
    new GpsPos(id,date,position,accuracy,heading,speed,distance,org,role,status)
  }

  def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
      .writeStringMember(ID, id)
      .writeDateTimeMember(DATE, date)

      .writeDoubleMember(LAT, position.latDeg)
      .writeDoubleMember(LON, position.lonDeg)
      .writeDoubleMember(ALT, position.altMeters)
      .writeDoubleMember(ACC, accuracy.toMeters)

      .writeDoubleMember(HDG, heading.toDegrees)
      .writeDoubleMember(SPD, speed.toMetersPerSecond)
      .writeDoubleMember(DIST, distance.toMeters)

      .writeIntMember(ORG, org)
      .writeIntMember(ROLE, role)
      .writeIntMember(STATUS, status)
  }

  override def serializeMembersFormattedTo (writer: JsonWriter): Unit = {
    writer
      .writeStringMember(ID, id)
      .writeDateTimeMember(DATE, date)

      .writeDoubleMember(LAT, position.latDeg,FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg,FMT_3_5)
      .writeDoubleMember(ALT, position.altMeters,FMT_6)
      .writeDoubleMember(ACC, accuracy.toMeters)

      .writeDoubleMember(HDG, heading.toDegrees, FMT_3)
      .writeDoubleMember(SPD, speed.toMetersPerSecond, FMT_3_2)
      .writeDoubleMember(DIST, distance.toMeters, FMT_6)

      .writeIntMember(ORG, org)
      .writeIntMember(ROLE, role)
      .writeIntMember(STATUS, status)
  }

  def serializeCsvMembersTo (ps: PrintStream): Unit = {
    ps.print(id); ps.print(',')
    ps.print(date.toEpochMillis); ps.print(',')

    ps.print(position.latDeg); ps.print(',')
    ps.print(position.lonDeg); ps.print(',')
    if (position.altitude.isDefined) ps.print(position.altMeters); ps.print(',')
    if (accuracy.isDefined) ps.print(accuracy.toMeters); ps.print(',')

    if (heading.isDefined) ps.print(heading.toDegrees); ps.print(',')
    if (speed.isDefined) ps.print(speed.toMetersPerSecond); ps.print(',')
    if (distance.isDefined) ps.print(distance.toMeters); ps.print(',')

    if (org != UndefinedOrg) ps.print(org); ; ps.print(',')
    if (role != UndefinedRole) ps.print(role); ps.print(',')
    if (status != UndefinedStatus) ps.print(status)
  }

  def serializeCsvTo (ps: PrintStream): Unit = {
    serializeCsvMembersTo(ps)
    ps.println()
  }
}

//--- Akka serialization support (for remote actors)

class GpsPosSerializer(system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[GpsPos](system) {
  override val initCapacity: Int = 64

  def serialize (gps: GpsPos): Unit = {
    writeUTF(gps.id)
    writeDateTime(gps.date)

    writeGeoPosition(gps.position)
    writeLength(gps.accuracy)

    writeAngle(gps.heading)
    writeSpeed(gps.speed)
    writeLength(gps.distance)

    writeInt(gps.org)
    writeInt(gps.role)
    writeInt(gps.status)
  }

  def deserialize(): GpsPos = {
    val id  = readUTF()
    val date = readDateTime()

    val pos = readGeoPosition()
    val accuracy = readLength()

    val hdg = readAngle()
    val spd = readSpeed()
    val dist = readLength()

    val org = readInt()
    val role = readInt()
    val status = readInt()

    new GpsPos(id, date, pos, accuracy, hdg, spd, dist, org,role,status)
  }
}

//--- archive support

/**
  * archive writer that stores as CSV with pre-pended archive/replay time (which can differ from position time)
  */
class GpsPosArchiveWriter(val oStream: OutputStream, val pathName: String="<unknown>") extends ArchiveWriter {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  val ps = new PrintStream(oStream)
  override def close(): Unit = ps.close()

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case gps: GpsPos =>
        ps.print(date.toEpochMillis)
        ps.print(',')
        gps.serializeCsvTo(ps)
        true
    }
  }
}

/**
  * corresponding archive reader
  */
class GpsPosArchiveReader(val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int)
                                                     extends StreamArchiveReader with GpsPosCsvParser {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))

  val lineBuffer = new LineBuffer(iStream,bufLen)

  override def readNextEntry(): Option[ArchiveEntry] = {
    if (lineBuffer.nextLine()) {
      if (initialize(lineBuffer)){
        val date = DateTime.ofEpochMillis(readNextValue().toLong)
        parseGpsPos() match {
          case SuccessValue(gps) => archiveEntry(date,gps)
          case Failure(msg) => None
        }
      } else None
    } else None
  }
}

//--- JSON parser

/**
  * JsonPullParser mixin to read GpsPos objects
  */
trait GpsPosJsonPullParser extends JsonPullParser {

  def parseGpsPos(): ResultValue[GpsPos] = {
    var id: String = null
    var lat: Angle = Angle.UndefinedAngle
    var lon: Angle = Angle.UndefinedAngle
    var alt: Length = Length.UndefinedLength
    var date: DateTime = DateTime.UndefinedDateTime
    var role: Int = UndefinedRole
    var org: Int = UndefinedDop
    var spd: Speed = Speed.UndefinedSpeed
    var hdg: Angle = Angle.UndefinedAngle
    var acc: Length = Length.UndefinedLength
    var dist: Length = Length.UndefinedLength
    var status: Int = TrackedObject.UndefinedStatus

    try {
      foreachMemberInCurrentObject {
        case ID => id = quotedValue.intern
        case DATE => date = DateTime.ofEpochMillis(unQuotedValue.toLong)
        case LAT => lat = Degrees(unQuotedValue.toDouble)
        case LON => lon = Degrees(unQuotedValue.toDouble)
        case ALT => alt = Meters(unQuotedValue.toDouble)
        case ACC => acc = Meters(unQuotedValue.toDouble)
        case SPD => spd = MetersPerSecond(unQuotedValue.toDouble)
        case HDG => hdg = Degrees(unQuotedValue.toDouble)
        case DIST => dist = Meters(unQuotedValue.toDouble)
        case ORG => org = unQuotedValue.toInt
        case ROLE => role = unQuotedValue.toInt
        case STATUS => status = unQuotedValue.toInt
      }

      gpsPosResult(id,date, lat,lon,alt,acc, hdg,spd,dist, org,role,status)

    } catch { // basic JSON parse error, likely corrupted data
      case x: Throwable => Failure(x.getMessage)
    }
  }
}

/**
  * class to parse GpsGroundPos lines
  */
trait GpsPosCsvParser extends Utf8CsvPullParser {

  def parseGpsPos(): ResultValue[GpsPos] = {
    def readInternString: String = {
      val v = readNextValue()
      if (v.isEmpty) null
      else v.intern
    }

    try {
      val id: String = readInternString
      val date: DateTime = DateTime.ofEpochMillis(readNextValue().toLong)

      val lat: Angle = Degrees(readNextValue().toDouble)
      val lon: Angle = Degrees(readNextValue().toDouble)
      val alt: Length = Meters(readNextValue().toDouble)
      val acc: Length = Meters(readNextValue().toOptionalDouble)

      val hdg: Angle = Degrees(readNextValue().toOptionalDouble)
      val spd: Speed = MetersPerSecond(readNextValue().toOptionalDouble)
      val dist: Length = Meters(readNextValue().toOptionalDouble)

      val org: Int = readNextValue().toInt
      val role: Int = readNextValue().toInt
      val status: Int = readNextValue().toInt
      skipToEndOfRecord()

      gpsPosResult(id,date, lat,lon,alt,acc, hdg,spd,dist, org,role,status)

    } catch { // basic CSV parse error, likely corrupted data
      case x: Throwable => Failure(x.getMessage)
    }
  }
}