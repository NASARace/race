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

import akka.actor.ExtendedActorSystem
import gov.nasa.race.common.AssocSeqImpl
import gov.nasa.race.core.{AkkaSerializer, SingleTypeAkkaSerializer}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.PlannedTrack
import gov.nasa.race.uom.{Angle, DateTime, Speed}

import scala.collection.mutable.ArrayBuffer

object SfdpsTracks {
  val empty = new ArrayBuffer[SfdpsTrack](0) with SfdpsTracks {
    override def assoc: String = ""
  }
}

/**
  * a TrackedAircraft object obtained through SWIMs SFDPS service
  */
case class SfdpsTrack(
    id: String,
    cs: String,
    position: GeoPosition,
    speed: Speed,
    heading: Angle,
    vr: Speed,
    date: DateTime,
    status: Int,
    //--- SFDPS specific
    src: String, // originating ARTCC
    departurePoint: String = "?",
    departureDate: DateTime = DateTime.UndefinedDateTime, // actual
    arrivalPoint: String = "?",
    arrivalDate: DateTime = DateTime.UndefinedDateTime, // actual or estimate
    //.. and probably more to follow
  ) extends TrackedAircraft with PlannedTrack {

  def isArrivalPointDefined: Boolean = arrivalPoint != "?"
  def isDeparturePointDefined: Boolean = departurePoint != "?"
}


/**
  * matchable type that represents a collection of SfdpsTrack objects that originated from the same ARTCC
  * we need this to be a matchable collection type
  */
trait SfdpsTracks extends TrackedAircraftSeq[SfdpsTrack] {
  @inline final def artccId: String = assoc
}

//--- serializer support

trait SfdpsTrackSer extends AkkaSerializer {
  def serializeSfdpsTrack(t: SfdpsTrack): Unit = {
    writeUTF(t.id)
    writeUTF(t.cs)
    writeGeoPosition(t.position)
    writeSpeed(t.speed)
    writeAngle(t.heading)
    writeSpeed(t.vr)
    writeDateTime(t.date)
    writeInt(t.status)

    writeUTF(t.src)
    writeUTF(t.departurePoint)
    writeDateTime(t.departureDate)
    writeUTF(t.arrivalPoint)
    writeDateTime(t.arrivalDate)
  }

  def deserializeSfdpsTrack(): SfdpsTrack = {
    val id = readUTF()
    val cs = readUTF()
    val pos = readGeoPosition()
    val spd = readSpeed()
    val hdg = readAngle()
    val vr = readSpeed()
    val date = readDateTime()
    val status = readInt()

    val src = readUTF()
    val departurePoint = readUTF()
    val departureDate = readDateTime()
    val arrivalPoint = readUTF()
    val arrivalDate = readDateTime()

    SfdpsTrack(id,cs,pos,spd,hdg,vr,date,status,src,departurePoint,departureDate,arrivalPoint,arrivalDate)
  }
}

class SfdpsTrackSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[SfdpsTrack](system) with SfdpsTrackSer {
  override def serialize(t: SfdpsTrack): Unit = serializeSfdpsTrack(t)
  override def deserialize(): SfdpsTrack = deserializeSfdpsTrack()
}

class SfdpsTracksImpl (assoc: String, elems: Array[SfdpsTrack]) extends AssocSeqImpl[SfdpsTrack,String](assoc,elems) with SfdpsTracks

class SfdpsTracksSerializer (system: ExtendedActorSystem) extends  SingleTypeAkkaSerializer[SfdpsTracks](system) with SfdpsTrackSer {
  override def serialize(t: SfdpsTracks): Unit = {
    writeUTF(t.assoc)
    writeItems(t)(serializeSfdpsTrack)
  }

  override def deserialize(): SfdpsTracks = {
    val assoc = readUTF()
    val tracks = readItems(deserializeSfdpsTrack)
    new SfdpsTracksImpl(assoc,tracks)
  }
}

