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
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.BusEvent
import gov.nasa.race.track.{TrackedObject, TrackedObjects}

import scala.collection.immutable.{Iterable, TreeSeqMap}

object TrackWSRoute {
  val TRACK = asc("track")
  val TRACK_LIST = asc("trackList")
  val SRC = asc("src")
}
import TrackWSRoute._

/**
  * a RaceRoute that pushes TrackedObject updates over a websocket connection
  */
trait TrackWSRoute extends BasicPushWSRaceRoute {
  val flatten = config.getBooleanOrElse("flatten", false)
  val writer = new JsonWriter()

  val channelMap: Map[String,String] = TreeSeqMap.from(config.getKeyValuePairsOrElse("channel-map", Seq.empty)) // preserve order

  // TBD - this will eventually handle client selections
  override protected def handleIncoming (ctx: BasicWSContext, m: Message): Iterable[Message] = {
    info(s"ignoring incoming message $m")
    discardMessage(m)
    Nil
  }

  def writeTrackObject (w: JsonWriter, channel: String, track: TrackedObject): Unit = {
    w.beginObject
    track.serializeMembersFormattedTo(w)
    w.writeStringMember(SRC, channelMap.getOrElse(channel,channel))
    w.endObject
  }

  def serializeTrack (channel: String, track: TrackedObject): Unit = {
    writer.clear().writeObject { w=>
      w.writeMemberName(TRACK)
      writeTrackObject(w,channel,track)
    }
  }

  def serializeTracks[T<:TrackedObject] (channel: String, tracks: TrackedObjects[T]): Unit = {
    writer.clear()
      .beginObject
      .writeMemberName(TRACK_LIST)
      .writeArray { w=>
        tracks.foreach( writeTrackObject(w,channel,_))
      }
      .endObject
  }

  // called from associated actor (different thread)
  override def receiveData: Receive = {
    case BusEvent(channel,track: TrackedObject,_) =>
      synchronized {
        serializeTrack(channel, track)
        push( TextMessage.Strict(writer.toJson))
      }

    case BusEvent(channel,tracks: TrackedObjects[_],_) =>
      synchronized {
        if (flatten) {
          tracks.foreach { track =>
            serializeTrack(channel, track)
            push(TextMessage.Strict(writer.toJson))
          }
        } else {
          serializeTracks(channel, tracks)
          push(TextMessage.Strict(writer.toJson))
        }
      }

    case _ => // ignore
  }
}
