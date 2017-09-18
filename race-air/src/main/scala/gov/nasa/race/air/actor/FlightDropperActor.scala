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

package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.air.{FlightCompleted, FlightCsChanged, FlightDropped, FlightPos}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.SubscribingRaceActor

import scala.collection.mutable

/**
  * actor that publishes FlightDropped messages for FlightPos objects which haven't been
  * updated for a configurable amount of (sim-)time
  *
  * nothing we have to add here, it's all in FPosDropper, but we might move the flights map
  * into the concrete class at some point
  */
class FlightDropperActor(val config: Config) extends SubscribingRaceActor with FPosDropper {
  val flights = mutable.HashMap.empty[String,FlightPos]

  override def removeStaleFlight(fpos: FlightPos) = flights -= fpos.cs

  override def handleMessage = handleFPosDropperMessage orElse {
    case BusEvent(_,fpos: FlightPos,_) => flights += fpos.cs -> fpos
    case BusEvent(_,fcompleted: FlightCompleted,_) => flights -= fcompleted.cs
    case BusEvent(_,_:FlightDropped,_) => // ignore - we are generating these
    case BusEvent(_,_:FlightCsChanged,_) => // ignore
  }
}
