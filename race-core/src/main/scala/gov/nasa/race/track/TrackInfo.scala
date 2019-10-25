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

import gov.nasa.race._
import gov.nasa.race.trajectory.{Trajectory=>Traj}
import gov.nasa.race.uom.DateTime

import scala.collection.Seq

/**
  * helper type to support erasure safe type matching
  */
case class TrackInfos (tInfos: Seq[TrackInfo]) {
  override def toString = {
    val sb = new StringBuilder
    sb.append("TrackInfos = [")
    tInfos.foreach( t => { sb.append("\n"); sb.append(t) } )
    sb.append(" ]")
    sb.toString()
  }
}

/**
  * meta info for tracks, such as departure and arrival location and times, flight plans etc.
  * This is supposed to be an accumulator that can be updated from different sources to collect all
  * information about a certain track, hence it depends on global ids ('cs')
  *
  * note we don't keep this as a case class since we want to be able to extend it
  * but the identifiers are optional since they can trickle in over time. This is supposed to
  * be an accumulative data structure with potentially different sources, both static (initialization)
  * and dynamic (messages)
  */
class TrackInfo(val trackRef: String,
                val cs: String,

                val trackCategory: Option[String],
                val trackType: Option[String],

                val departurePoint: Option[String],
                val arrivalPoint: Option[String],

                val etd: DateTime,
                val atd: DateTime,
                val eta: DateTime,
                val ata: DateTime,

                val route: Option[Traj]
                // ... and more to follow
               ) {

  override def toString = { // compact version
    val sb = new StringBuilder
    sb.append(s"TrackInfo{cs:$cs")
    ifSome(trackType){ s=> sb.append(s",type:$s")}
    ifSome(departurePoint){ s=> sb.append(s",from:$s")}
    ifSome(arrivalPoint){ s=> sb.append(s",to:$s")}
    if (atd.isDefined) sb.append(s",atd:${atd}") else if (etd.isDefined) sb.append(s",etd:${etd}")
    if (ata.isDefined) sb.append(s",ata:${ata}") else if (eta.isDefined) sb.append(s",eta:${eta}")
    sb.append('}')
    sb.toString
  }

  def accumulate (prev: TrackInfo): TrackInfo = {
    new TrackInfo( trackRef, cs,
      trackCategory.orElse(prev.trackCategory),
      trackType.orElse(prev.trackType),
      departurePoint.orElse(prev.departurePoint),
      arrivalPoint.orElse(prev.arrivalPoint),
      etd.orElse(prev.etd),
      atd.orElse(prev.atd),
      eta.orElse(prev.eta),
      ata.orElse(prev.ata),
      route.orElse(prev.route)
    )
  }

  def accumulate (maybePref: Option[TrackInfo]): TrackInfo = {
    maybePref match {
      case Some(prev) => accumulate(prev)
      case None => this
    }
  }
}

//--- one time request/response (updates are handled through ChannelTopicRequests)
case class RequestTrackInfo(cs: String) // a one-time request for a given call sign
case class NoSuchTrackInfo(cs: String)

// our channel topic type (we keep this separate from one-time requests)
case class TrackInfoUpdateRequest(cs: String)

