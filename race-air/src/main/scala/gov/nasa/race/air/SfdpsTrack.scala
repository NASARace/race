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

import gov.nasa.race.common.AssocSeq
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.{Angle, DateTime, Speed}

import scala.collection.mutable.ArrayBuffer

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
    src: String // originating ARTCC
    //.. and probably more to follow
  ) extends TrackedAircraft {
}

/**
  * matchable type that represents a collection of SfdpsTrack objects that originated from the same ARTCC
  */
trait SfdpsTracks extends TrackedAircraftSeq[SfdpsTrack] {
  @inline final def artccId: String = assoc
}

object SfdpsTracks {
  val empty = new ArrayBuffer[SfdpsTrack](0) with SfdpsTracks {
    override def assoc: String = ""
  }
}