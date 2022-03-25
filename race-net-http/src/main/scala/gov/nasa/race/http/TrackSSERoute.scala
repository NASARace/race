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
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import com.typesafe.config.Config
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, ParentActor}
import gov.nasa.race.track.{Tracked3dObject, TrackedObjects}

import scala.collection.Seq

/**
  * a RaceRoute that pushed TrackedObject updates as SSEs
  */
trait TrackSSERoute extends PushSSERoute {
  val flatten = config.getBooleanOrElse("flatten", false)
  val writer = new JsonWriter()

  def receiveTrackData: Receive = {
    case BusEvent(_,track: Tracked3dObject,_) =>
      synchronized {
        writer.clear()
        track.serializeFormattedTo(writer)
        push( new ServerSentEvent(writer.toJson, Some("track")))
      }
    case BusEvent(_,tracks:TrackedObjects[_],_) =>
      synchronized {
        if (flatten) {
          tracks.map( t=> ServerSentEvent(writer.toNewJson(t), Some("track")))
        } else {
          writer.clear()
          tracks.serializeFormattedTo(writer)
          Seq(new ServerSentEvent(writer.toJson, Some("trackList")))
        }.foreach(push)
      }

  }

  override def receiveData: Receive = receiveTrackData.orElse(super.receiveData)
}

/**
  * a TrackRoute that gets requested from a site document
  */
class TrackSiteSSERoute(val p: ParentActor, val c: Config) extends SiteRoute(p,c) with TrackSSERoute {

  val trackPath = s"$requestPrefix/tracks"
  val trackPathMatcher = PathMatchers.separateOnSlashes(trackPath)

  def trackRoute: Route = {
    get {
      path(trackPathMatcher) {
        promoteToStream()
      }
    }
  }

  override def route = trackRoute ~ siteRoute
}
