/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import gov.nasa.race.IdentifiableObject
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{AssocSeq, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.StringUtils

import java.text.DecimalFormat
import scala.reflect.{ClassTag, classTag}

object TrackedObject {

  //--- track status flags
  // note the lower 2 bytes are reserved for general flags defined here,
  // channel specific flags should use the upper two

  final val TrackNoStatus: Int  = 0x00
  final val NewFlag: Int        = 0x01
  final val ChangedFlag: Int    = 0x02
  final val DroppedFlag: Int    = 0x04
  final val CompletedFlag: Int  = 0x08
  final val FrozenFlag: Int     = 0x10
  final val ChangedCSFlag: Int  = 0x20

  def tempCS (flightId: String) = "?" + flightId
  def isTempCS (cs: String) = cs.charAt(0) == '?'

  // c/s changes only happen rarely, and if they do we want to preserve the changed value
  // for all downstream actors so we don't use a fixed field for it
  case class ChangedCS(oldCS: String)

  case class TrackProblem(fpos: TrackedObject, lastFpos: TrackedObject, problem: String)

  // lexical constants
  val ID = asc("id")
  val LABEL = asc("label")
  val DATE = asc("date")
  val LAT = asc("lat")
  val LON = asc("lon")
  val ALT = asc("alt")
  val HDG = asc("hdg")
  val SPD = asc("spd")
  val VR = asc("vr")
  val ROLL = asc("roll")
  val PITCH = asc("pitch")
  val STATUS = asc("status")

  val FMT_3_2 = new DecimalFormat("###.##")
  val FMT_3_5 = new DecimalFormat("###.#####")
  val FMT_3 = new DecimalFormat("###")
  val FMT_6 = new DecimalFormat("######")
}

/**
  * this is the abstract type used for sea, land, air and space objects we can track
  *
  * in addition to the super traits, TrackedObjects provide an API to attach arbitrary
  * non-type-constraint data to its instances. This is a generic extension mechanism for
  * cases where occasionally associated data (a) should not be type restricted and (b)
  * are set too rarely to waste storage on all TrackObject instances
  */
trait TrackedObject extends IdentifiableObject with TrackPoint with MovingObject with AttitudeObject with TrackMessage with JsonSerializable {
  import TrackedObject._

  // generic mechanism to dynamically attach per-event data to track objects
  var amendments = List.empty[Any]

  def status: Int // flag field, can be channel specific (hence no enum)

  def isNew = (status & NewFlag) != 0
  def isChanged = (status & ChangedFlag) != 0
  def isDropped = (status & DroppedFlag) != 0
  def isCompleted = (status & CompletedFlag) != 0
  def isDroppedOrCompleted = (status & (DroppedFlag|CompletedFlag)) != 0
  def isFrozen = (status & FrozenFlag) != 0
  def isChangedCS: Boolean = (status & ChangedCSFlag) != 0

  def amend (a: Any): TrackedObject = { amendments = a +: amendments; this }
  def amendAll (as: Any*) = { as.foreach(amend); this }
  def getAmendment (f: (Any)=> Boolean): Option[Any] = amendments.find(f)
  def getFirstAmendmentOfType[T: ClassTag]: Option[T] = {
    val tgtCls = classTag[T].runtimeClass
    amendments.find( a=> tgtCls.isAssignableFrom(a.getClass)).map( _.asInstanceOf[T])
  }

  def toShortString = {
    val d = date
    val hh = d.getHour
    val mm = d.getMinute
    val ss = d.getSecond
    val id = StringUtils.capLength(cs)(8)
    f"$id%-7s $hh%02d:$mm%02d:$ss%02d ${position.altitude.toFeet.toInt}%6dft ${heading.toNormalizedDegrees.toInt}%3d° ${speed.toKnots.toInt}%4dkn"
  }

  def hasTempCS = isTempCS(cs)
  def tempCS = if (hasTempCS) cs else TrackedObject.tempCS(id)
  def getOldCS: Option[String] = amendments.find(_.isInstanceOf[ChangedCS]).map(_.asInstanceOf[ChangedCS].oldCS)

  //--- JSON formatting

  def serializeFormattedTo (writer: JsonWriter): Unit = {
    writer
      .beginObject
      .writeStringMember(ID, id)
      .writeStringMember(LABEL, cs)
      .writeDateTimeMember(DATE, date)
      .writeUnQuotedMember(LAT, FMT_3_5.format(position.latDeg))
      .writeUnQuotedMember(LON, FMT_3_5.format(position.lonDeg))
      .writeUnQuotedMember(ALT, FMT_6.format(position.altMeters))
      .writeUnQuotedMember(HDG, FMT_3.format(heading.toDegrees))
      .writeUnQuotedMember(SPD, FMT_3_2.format(speed.toMetersPerSecond))

    if (vr.isDefined) writer.writeUnQuotedMember(VR, FMT_3_2.format(vr.toMetersPerSecond))
    if (pitch.isDefined) writer.writeUnQuotedMember(PITCH, FMT_3.format(pitch.toDegrees))
    if (roll.isDefined) writer.writeUnQuotedMember(ROLL, FMT_3.format(roll.toDegrees))

    writer
      .writeIntMember(STATUS, status)
      .endObject
  }

  def serializeFormattedAs (writer: JsonWriter, key: String): Unit = {
    writer.writeMemberName(key)
    serializeFormattedTo(writer)
  }

  def serializeTo (writer: JsonWriter): Unit = {
    writer
      .beginObject
      .writeStringMember(ID, id)
      .writeStringMember(LABEL, cs)
      .writeDateTimeMember(DATE, date)
      .writeDoubleMember(LAT, position.latDeg)
      .writeDoubleMember(LON, position.lonDeg)
      .writeDoubleMember(ALT, position.altMeters)
      .writeDoubleMember(HDG, heading.toDegrees)
      .writeDoubleMember(SPD, speed.toMetersPerSecond)
      .writeDoubleMember(VR, vr.toMetersPerSecond)
      .writeDoubleMember(PITCH, pitch.toDegrees)
      .writeDoubleMember(ROLL, roll.toDegrees)
      .writeIntMember(STATUS, status)
      .endObject
  }

  def serializeAs (writer: JsonWriter, key: String): Unit = {
    writer.writeMemberName(key)
    serializeTo(writer)
  }
}

/**
  * a track that has an associated plan, which at least contains information about where it
  * came from and where it is going to
  */
trait PlannedTrack {
  def departurePoint: String
  def departureDate: DateTime
  def arrivalPoint: String
  def arrivalDate: DateTime
}

trait TrackedObjects[+T <: TrackedObject] extends AssocSeq[T,String] {

  def serializeTo (writer: JsonWriter): Unit = {
    writer.beginArray
    foreach( _.serializeTo(writer))
    writer.endArray
  }
  def serializeAs (writer: JsonWriter, key: String): Unit = {
    writer.writeMemberName(key)
    serializeTo(writer)
  }

  def serializeFormattedTo (writer: JsonWriter): Unit = {
    writer.beginArray
    foreach( _.serializeFormattedTo(writer))
    writer.endArray
  }
  def serializeFormattedAs (writer: JsonWriter, key: String): Unit = {
    writer.writeMemberName(key)
    serializeFormattedTo(writer)
  }
}

trait TrackedObjectEnumerator {
  def numberOfTrackedObjects: Int
  def foreachTrackedObject (f: TrackedObject=>Unit): Unit
}