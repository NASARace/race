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
import gov.nasa.race.common._
import gov.nasa.race.core.{AkkaSerializer, SingleTypeAkkaSerializer}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.{Tracked3dObject, TrackedObjects}
import gov.nasa.race.uom.{DateTime, _}

import scala.collection.mutable.ArrayBuffer

object AsdexTrack {
  // AsdexTrack specific status flags (>0xffff)
  final val DisplayFlag: Int  =  0x10000
  final val OnGroundFlag: Int =  0x20000
  final val VehicleFlag: Int  =  0x40000
  final val AircraftFlag: Int =  0x80000
  final val UpFlag: Int       = 0x100000
  final val DownFlag: Int     = 0x200000

  final val noVehicleFlags = AircraftFlag | UpFlag | DownFlag
}

case class AsdexTrack(id: String,
                      cs: String,
                      position: GeoPosition,
                      speed: Speed,
                      heading: Angle,
                      vr: Speed,
                      date: DateTime,
                      status: Int,
                      src: String, // originating airport
                      acType: Option[String]) extends Tracked3dObject {
  import AsdexTrack._

  def isAircraft = (status & AircraftFlag) != 0
  def isGroundAircraft = ((status & OnGroundFlag) != 0) && !position.hasDefinedAltitude
  def isAirborne = position.hasDefinedAltitude
  def isMovingGroundAircraft = isGroundAircraft && heading.isDefined
  def isAirborneAircraft = isAircraft && position.hasDefinedAltitude
  def isVehicle = (status & VehicleFlag) != 0
  def isUp = (status & UpFlag) != 0
  def isDown = (status & DownFlag) != 0

  override def source: Option[String] = Some(src)

  // some airports are not setting flags appropriately
  def guessAircraft: Boolean =  (status & AircraftFlag) != 0

  override def toShortString = s"Track{$id,0x${status.toHexString},$position,$date}"
}

/**
  * a matchable type for a Seq of AsdexTracks reported by the same airport
  */
trait AsdexTracks extends TrackedObjects[AsdexTrack] {
  @inline final def airportId: String = assoc
}

object AsdexTracks {
  // there is no concrete immutable Seq type we can extend?
  val empty: AsdexTracks = new ArrayBuffer[AsdexTrack](0) with AsdexTracks {
    override def assoc: String = ""
  }
}

class AsdexTracksImpl(assoc: String, elems: Array[AsdexTrack]) extends AssocSeqImpl(assoc,elems) with AsdexTracks


//--- serializer support

trait AsdexTrackSer extends AkkaSerializer {
  def serializeAsdexTrack (t: AsdexTrack): Unit = {
    writeUTF(t.id)
    writeUTF(t.cs)
    writeGeoPosition(t.position)
    writeSpeed(t.speed)
    writeAngle(t.heading)
    writeSpeed(t.vr)
    writeDateTime(t.date)
    writeInt(t.status)

    writeUTF(t.src)
    writeUTF( t.acType.getOrElse(""))
  }

  def deserializeAsdexTrack (): AsdexTrack = {
    val id  = readUTF()
    val cs  = readUTF()
    val pos = readGeoPosition()
    val spd = readSpeed()
    val hdg = readAngle()
    val vr  = readSpeed()
    val date = readDateTime()
    val status = readInt()

    val src = readUTF()
    val act = readUTF()
    val acType = if (act.isEmpty) None else Some(act)

    AsdexTrack(id,cs,pos,spd,hdg,vr,date,status,src,acType)
  }
}

class AsdexTrackSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[AsdexTrack](system) with AsdexTrackSer {
  override def serialize(t: AsdexTrack): Unit = serializeAsdexTrack(t)
  override def deserialize(): AsdexTrack = deserializeAsdexTrack()
}

class AsdexTracksSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[AsdexTracks](system) with AsdexTrackSer {
  override def serialize(t: AsdexTracks): Unit = {
    writeUTF(t.assoc)
    writeItems(t)(serializeAsdexTrack)
  }

  override def deserialize(): AsdexTracks = {
    val assoc = readUTF()
    val tracks = readItems(deserializeAsdexTrack)
    new AsdexTracksImpl(assoc,tracks)
  }
}