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
package gov.nasa.race.ww.air

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.TATrack
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.ww.{DynamicRaceLayerInfo, LayerInfoPanel, RaceView, SubscribingRaceLayer}


/**
  * a layer to display TRACONs and related TATracks
  */
class TATracksLayer (raceView: RaceView,config: Config) extends SubscribingRaceLayer(raceView,config) with DynamicRaceLayerInfo{
  override def size: Int = 0

  /**
    * this has to be implemented in the concrete RaceLayer. It is executed in the
    * event dispatcher
    */
  override def handleMessage: Receive = {
    case BusEvent(_, track: TATrack, _) =>
  }

  override val panel: LayerInfoPanel = null
}
