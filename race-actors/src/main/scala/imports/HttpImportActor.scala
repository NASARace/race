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

package gov.nasa.race.actors.imports

import akka.actor.{ActorRef, Cancellable}
import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, Response}
import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core.{Bus, BusEvent, PublishingRaceActor, RaceContext, SubscribingRaceActor}
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


class HttpImportActor (val config: Config) extends PublishingRaceActor with SubscribingRaceActor {

  class ResponseHandler extends AsyncCompletionHandler[Unit]() {
    def onCompleted(response: Response): Unit = {
      val msg = response.getResponseBody
      info(f"handler received response $msg%20.20s.. ")
      publish(msg)
    }
  }

  case object SendRequest

  val uri = config.getString("uri")

  // if 0 we only request once upon start, or if somebody sends us a SendRequest
  val requestInterval = config.getFiniteDurationOrElse("interval", 0 seconds)

  val handler = new ResponseHandler()
  val client = new AsyncHttpClient()

  var optSchedule: Option[Cancellable] = None

  override def handleMessage = {
    case SendRequest | BusEvent(_,SendRequest,_) =>
      info(f"sending request to ${uri}%20.20s..")
      client.prepareGet(uri).execute(handler)
  }


  //--- system message callbacks
  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)

    if (requestInterval.length > 0) {
      optSchedule = scheduleNow(requestInterval,SendRequest)
    } else {
      self ! SendRequest
    }
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)
    ifSome(optSchedule){ _.cancel }
    client.close
  }
}
