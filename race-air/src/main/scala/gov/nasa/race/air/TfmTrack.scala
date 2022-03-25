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
import gov.nasa.race.common.{ArraySeqImpl, AssocSeqImpl}
import gov.nasa.race.core.{AkkaSerializer, SingleTypeAkkaSerializer}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
import gov.nasa.race.track.Tracked3dObject
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom._

import scala.collection.Seq
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer


/**
  * object representing a Traffic Flow Management (TFM) track update
  */
case class TfmTrack(id: String,
                    cs: String,
                    position: GeoPosition,
                    speed: Speed,
                    date: DateTime,
                    status: Int,

                    src: String,
                    nextPos: Option[GeoPosition],
                    nextDate: DateTime // might be undefined
                   ) extends Tracked3dObject {

  val heading = if (nextPos.isDefined) GreatCircle.initialBearing(position,nextPos.get) else Degrees(0)
  def vr = Speed.UndefinedSpeed // not in current data model

  override def source: Option[String] = Some(src)
}

/**
  * matchable type for a collection of TFMTracks
  */
trait TfmTracks extends Seq[TfmTrack]

object TfmTracks {
  val empty = new ArrayBuffer[TfmTrack](0) with TfmTracks
}

class TfmTracksImpl (elems: Array[TfmTrack]) extends ArraySeqImpl[TfmTrack](elems) with TfmTracks

//--- serializer support

trait TfmTrackSer extends AkkaSerializer {
  def serializeTfmTrack (t: TfmTrack): Unit = {
    writeUTF(t.id)
    writeUTF(t.cs)
    writeGeoPosition(t.position)
    writeSpeed(t.speed)
    writeDateTime(t.date)
    writeInt(t.status)

    writeUTF(t.src)
    if (writeIsDefined(t.nextPos)) writeGeoPosition(t.nextPos.get)
    writeDateTime(t.nextDate)
  }

  def deserializeTfmTrack (): TfmTrack = {
    val id  = readUTF()
    val cs  = readUTF()
    val pos = readGeoPosition()
    val spd = readSpeed()
    val date = readDateTime()
    val status = readInt()

    val src = readUTF()
    val nextPos = if (readIsDefined()) Some(readGeoPosition()) else None
    val nextDate = readDateTime()

    TfmTrack(id,cs,pos,spd,date,status,src,nextPos,nextDate)
  }
}

class TfmTrackSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[TfmTrack](system) with TfmTrackSer {
  override def serialize(t: TfmTrack): Unit = serializeTfmTrack(t)
  override def deserialize(): TfmTrack = deserializeTfmTrack()
}

class TfmTracksSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[TfmTracks](system) with TfmTrackSer {
  override def serialize(t: TfmTracks): Unit = {
    writeItems(t)(serializeTfmTrack)
  }

  override def deserialize(): TfmTracks = {
    val tracks = readItems(deserializeTfmTrack)
    new TfmTracksImpl(tracks)
  }
}