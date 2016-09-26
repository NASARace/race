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

package gov.nasa.race.actors.translators

import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.core.{BusEvent, ContinuousTimeRaceActor, SubscribingRaceActor, PublishingRaceActor}

/**
 * a generic actor that translates times by means of a configured (object specific) translator
 */
class TimeTranslatorActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor with ContinuousTimeRaceActor {

  val translator = createTranslator( config.getConfig("translator"))

  override def handleMessage = {
    case BusEvent(_, obj: Any, _)  =>
      val objʹ = translator.translate(obj, simTime)
      publish(objʹ)
  }

  def createTranslator (config: Config): ConfigurableTimeTranslator = {
    val translator = newInstance[ConfigurableTimeTranslator]( config.getString("class"), Array(classOf[Config]), Array(config)).get
    info(s"instantiated time translator ${translator.name}")
    translator
  }
}
