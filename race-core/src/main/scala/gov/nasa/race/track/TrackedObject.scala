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
import gov.nasa.race.common.{AssocSeq, FMT_3, FMT_3_2, FMT_3_5, FMT_6, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.{Angle, DateTime, Speed}
import gov.nasa.race.util.StringUtils

import java.text.DecimalFormat
import scala.reflect.{ClassTag, classTag}

object TrackedObject {

  //--- track status flags
  // note the lower 2 bytes are reserved for general flags defined here,
  // channel specific flags should use the upper two
  val UndefinedStatus: Int = 0

  val NoStatus: Int       = 0x00
  val NewFlag: Int        = 0x01
  val ChangedFlag: Int    = 0x02
  val DroppedFlag: Int    = 0x04
  val CompletedFlag: Int  = 0x08
  val FrozenFlag: Int     = 0x10
  val ChangedCSFlag: Int  = 0x20

  //--- display attribute flags
  val NoDsp               = 0x00
  val Dsp2dFlag           = 0x01  // object has 2d attitude
  val Dsp3dFlag           = 0x02  // object has 3d attitude
  val DspGroundFlag       = 0x04  // display on ground
  val DspRelativeFlag     = 0x08  // alt is relative to ground

  def tempCS (flightId: String) = "?" + flightId
  def isTempCS (cs: String) = cs.charAt(0) == '?'

  // c/s changes only happen rarely, and if they do we want to preserve the changed value
  // for all downstream actors so we don't use a fixed field for it
  case class ChangedCS(oldCS: String)

  case class TrackProblem(fpos: Tracked3dObject, lastFpos: Tracked3dObject, problem: String)

  // lexical constants
  val ID = asc("id")
  val LABEL = asc("label")
  val DATE = asc("date")
  val LAT = asc("lat")
  val LON = asc("lon")
  val ALT = asc("alt")

  val STATUS = asc("status")
  val DSP = asc("dsp")

  val HDG = asc("hdg")
  val SPD = asc("spd")

  val VR = asc("vr")
  val ROLL = asc("roll")
  val PITCH = asc("pitch")

  val ROLE = asc("role")
  val ORG = asc("org")
}

/**
  * object that moves in 3d but does not have an orientation (attitude)
  *
  * this adds track status and optional amendments
  */
trait TrackedObject extends IdentifiableObject with TrackPoint with JsonSerializable {
  import TrackedObject._

  // generic mechanism to dynamically attach per-event data to track objects
  var amendments = List.empty[Any]

  def status: Int // flag field, can be channel specific (hence no enum)

  // display relevant attributes we associate with the concrete type (i.e. don't have to store in instances)
  def displayAttrs: Int = Dsp3dFlag | Dsp2dFlag

  def isClampedToGround: Boolean = (displayAttrs & DspGroundFlag) != 0 // display on the ground
  def isRelativeToGround: Boolean = (displayAttrs & DspRelativeFlag) != 0
  def has3dAttitude: Boolean = (displayAttrs & Dsp3dFlag) != 0 // display heading/role/pitch
  def has2dAttitude: Boolean = (displayAttrs & Dsp2dFlag) != 0 // display just heading
  def hasNoAttitude: Boolean = (displayAttrs & (Dsp2dFlag | Dsp3dFlag)) == 0

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

  // FIXME - leftover from ads-b tracks
  def hasTempCS = isTempCS(cs)
  def tempCS = if (hasTempCS) cs else TrackedObject.tempCS(id)
  def getOldCS: Option[String] = amendments.find(_.isInstanceOf[ChangedCS]).map(_.asInstanceOf[ChangedCS].oldCS)
}

/**
  * object that is a moving 3D point, i.e. has a horizontal and vertical speed vector
  */
trait Moving3dObject {
  def heading: Angle
  def speed: Speed
  def vr: Speed   // vertical rate
}

/**
  * non-point object that has an orientation relative to the horizontal plane
  * note that pitch and roll default to 0, i.e. have to be overridden if the concrete type sets them
  */
trait AttitudeObject {
  def pitch: Angle = Angle.UndefinedAngle
  def roll: Angle = Angle.UndefinedAngle
}

/**
  * this is the abstract type used for sea, land, air and space objects we can track
  *
  * in addition to the super traits, TrackedObjects provide an API to attach arbitrary
  * non-type-constraint data to its instances. This is a generic extension mechanism for
  * cases where occasionally associated data (a) should not be type restricted and (b)
  * are set too rarely to waste storage on all TrackObject instances
  */
trait Tracked3dObject extends TrackedObject with Moving3dObject with AttitudeObject with TrackMessage {
  import TrackedObject._

  def toShortString = {
    val d = date
    val hh = d.getHour
    val mm = d.getMinute
    val ss = d.getSecond
    val id = StringUtils.capLength(cs)(8)
    f"$id%-7s $hh%02d:$mm%02d:$ss%02d ${position.altitude.toFeet.toInt}%6dft ${heading.toNormalizedDegrees.toInt}%3d° ${speed.toKnots.toInt}%4dkn"
  }

  //--- JSON formatting

  override def serializeMembersFormattedTo (writer: JsonWriter): Unit = {
    writer
      .writeStringMember(ID, id)
      .writeStringMember(LABEL, cs)
      .writeDateTimeMember(DATE, date)
      .writeDoubleMember(LAT, position.latDeg, FMT_3_5)
      .writeDoubleMember(LON, position.lonDeg, FMT_3_5)
      .writeDoubleMember(ALT, position.altMeters, FMT_6)
      .writeDoubleMember(HDG, heading.toDegrees, FMT_3)
      .writeDoubleMember(SPD, speed.toMetersPerSecond, FMT_3_2)
      .writeDoubleMember(VR, vr.toMetersPerSecond, FMT_3_2)
      .writeDoubleMember(PITCH, pitch.toDegrees, FMT_3)
      .writeDoubleMember(ROLL, roll.toDegrees, FMT_3)
      .writeIntMember(STATUS, status)
  }

  def serializeMembersTo (writer: JsonWriter): Unit = {
    writer
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
  def foreachTrackedObject (f: Tracked3dObject=>Unit): Unit
}