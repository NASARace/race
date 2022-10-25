/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.http

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonProducer, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, RaceDataClient}
import gov.nasa.race.track.{TrackDropped, TrackedObject, TrackedObjects}

import scala.collection.immutable.{Iterable, TreeSeqMap}

object TrackWSRoute {
  val TRACK = asc("track")
  val TRACK_LIST = asc("trackList")
  val SRC = asc("src")
  val DSP = asc("dsp")
  val SYM = asc("sym")
  val DROP = asc("drop")
  val ID = asc("id")
  val DATE = asc("date")
  val LABEL = asc("label")
}
import gov.nasa.race.http.TrackWSRoute._

/**
  * a RaceRoute that pushes TrackedObject updates over a websocket connection
  *
  * Note that we don't imply the client processing here (e.g. Cesium visualization) - this only handles the track data update
  * No assets are associated with this route fragment
  */
trait TrackWSRoute extends PushWSRaceRoute with JsonProducer with RaceDataClient {

  val flatten = config.getBooleanOrElse("flatten", false)

  // map from bus channels (which are RACE-internal) to symbolic names (that can be used by clients etc.)
  val channelMap: Map[String,String] = TreeSeqMap.from(config.getKeyValuePairsOrElse("channel-map", Seq.empty)) // preserve order

  // TBD - this will eventually handle client selections
  override protected def handleIncoming (ctx: WSContext, m: Message): Iterable[Message] = {
    info(s"ignoring incoming message $m")
    discardMessage(m)
    Nil
  }

  // override in concrete route to use type/status/channel specific models or billboards
  def getTrackSymbol (channel: String, track: TrackedObject): Option[String] = {
    if (track.hasNoAttitude) Some("marker")
    else Some("model")
  }

  // override in concrete route type if we have specialized display needs
  def writeTrackObject (w: JsonWriter, channel: String, track: TrackedObject): Unit = {
    w.beginObject
    track.serializeMembersFormattedTo(w)
    w.writeStringMember(LABEL, track.cs) // FIXME - we have to make this more general

    //--- add our display related fields
    w.writeStringMember(SRC, channelMap.getOrElse(channel,channel)) // we always have one
    w.writeIntMember(DSP,track.displayAttrs)
    getTrackSymbol(channel,track).foreach( w.writeStringMember(SYM,_)) // optional

    w.endObject
  }

  def serializeTrack (writer: JsonWriter, channel: String, track: TrackedObject): Unit = {
    writer.writeObject { w=>
      w.writeMemberName(TRACK)
      writeTrackObject(w,channel,track)
    }
  }

  def serializeTracks[T<:TrackedObject](writer: JsonWriter, channel: String, tracks: TrackedObjects[T]): Unit = {
    writer
      .beginObject
      .writeMemberName(TRACK_LIST)
      .writeArray { w=>
        tracks.foreach( writeTrackObject(w,channel,_))
      }
      .endObject
  }

  def serializeDrop(writer: JsonWriter, channel: String, drop: TrackDropped): Unit = {
    writer
      .beginObject
      .writeMemberName(DROP)
      .beginObject
      .writeStringMember(ID,drop.id)
      .writeStringMember(LABEL, drop.cs)
      .writeDateTimeMember(DATE,drop.date)
      .writeStringMember(SRC, channelMap.getOrElse(channel,channel)) // we always have one
      .endObject
      .endObject
  }

  // NOTE - called from associated actor (different thread), use own writer!
  def receiveTrackData: Receive = {
    case BusEvent(channel,track: TrackedObject,_) =>
      val msg = toNewJson( serializeTrack(_,channel,track))
      push( TextMessage.Strict( msg))

    case BusEvent(channel,tracks: TrackedObjects[_],_) =>
      if (flatten) {
        tracks.foreach( track => push( TextMessage.Strict( toNewJson(serializeTrack(_,channel, track)))))
      } else {
        push( TextMessage.Strict( toNewJson( serializeTracks(_, channel, tracks))))
      }

    case BusEvent(channel,drop: TrackDropped,_) => push( TextMessage.Strict( toNewJson( serializeDrop(_, channel, drop))))
  }

  override def receiveData: Receive = receiveTrackData.orElse(super.receiveData)
}
