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

package gov.nasa.race.core

import com.typesafe.config.Config

/**
  * abstract bases for RaceActors written in Java
  *
  * Note - unfortunately we can't directly extend UntypedActor because
  * it has a final receive(), which doesn't play nicely with RaceActor.
  * This means we have to duplicate some Java'isms provided by UntypedRaceActor.
  */
abstract class UntypedRaceActor extends RaceActor {
  /**
    * the message handler for Java actors, which can't directly provide PartialFunctions
    * We work around the pattern matching by using a boolean return value in order to
    * determine if the function was defined for the argument
    */
  def onHandleMessage (msg: Object): Boolean = false

  override def receiveLive = {
    case msg: Object => if (!onHandleMessage(msg)) handleSystemMessage.apply(msg)
  }
}

abstract class SubscribingRaceActorBase (val config: Config) extends UntypedRaceActor with SubscribingRaceActor
abstract class PublishingRaceActorBase (val config: Config) extends UntypedRaceActor with PublishingRaceActor
abstract class PubSubRaceActorBase (val config: Config) extends UntypedRaceActor with SubscribingRaceActor with PublishingRaceActor
abstract class ContinuousTimeRaceActorBase (val config: Config) extends UntypedRaceActor with ContinuousTimeRaceActor
abstract class SubscribingCTRaceActorBase (val config: Config) extends UntypedRaceActor with SubscribingRaceActor with ContinuousTimeRaceActor
abstract class PublishingCTRaceActorBase (val config: Config) extends UntypedRaceActor with PublishingRaceActor with ContinuousTimeRaceActor
abstract class PubSubCTRaceActorBase (val config: Config) extends UntypedRaceActor with SubscribingRaceActor with PublishingRaceActor  with ContinuousTimeRaceActor

