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

package gov.nasa.race.ww

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.core.{RacePause, RacePauseRequest, RaceResumeRequest, RaceTerminateRequest}
import gov.nasa.race.core.{ContinuousTimeRaceActor, RaceContext, _}
import gov.nasa.race.swing._

import scala.concurrent.duration._


/**
  * the master actor for geospatial display of RACE data channels,
  * using NASA WorldWind for the heavy lifting
  *
  * Each layer is instantiated from the config, each SuscribingRaceLayer
  * has an associated actor which is created/supervised by this RaceViewer
  * instance
  */
class RaceViewerActor(val config: Config) extends ContinuousTimeRaceActor
               with SubscribingRaceActor with PublishingRaceActor with ParentRaceActor {

  var view: Option[RaceViewer] = None // we can't create this from a Akka thread since it does UI transcations

  var initTimedOut = false // WorldWind initialization might do network IO - we have to check for timeouts

  //--- RaceActor callbacks

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    scheduleOnce(initTimeout - 100.millisecond){
      if (!view.isDefined){
        warning("timeout during view initialization")
        initTimedOut = true
      }
    }

    invokeAndWait { // this is executed in the AWT event dispatch thread
      try {
        view = Some(new RaceViewer(RaceViewerActor.this))
      } catch {
        case x: Throwable => x.printStackTrace
      }
    }
    view.isDefined && !initTimedOut && super.onInitializeRaceActor(rc, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    ifSome(view) { v => invokeAndWait(v.onRaceStarted) }
    super.onStartRaceActor(originator)
  }

  override def onPauseRaceActor(originator: ActorRef) = {
    ifSome(view) { v => invokeAndWait(v.onRacePaused) }
    super.onPauseRaceActor(originator)
  }

  override def onResumeRaceActor(originator: ActorRef) = {
    ifSome(view) { v => invokeAndWait(v.onRaceResumed) }
    super.onResumeRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(view) { v =>
      if (v.displayable) {
        info(s"${name} closing WorldWind window..")
        invokeAndWait(v.onRaceTerminated)
        info(s"${name} WorldWind window closed")
      } else {
        info(s"${name} WorldWind window already closed")
      }
    }

    super.onTerminateRaceActor(originator)
  }
}

