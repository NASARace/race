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
import gov.nasa.race.core.{RaceContext, PublishingRaceActor, Bus}
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


class HttpImportActor (val config: Config) extends PublishingRaceActor {

  class ResponseHandler extends AsyncCompletionHandler[Unit]() {
    def onCompleted(response: Response): Unit = {
      val msg = response.getResponseBody
      log.info(f"${name} handler received response $msg%20.20s.. ")
      publish(writeTo, msg)
    }
  }

  //--- local messages
  case object SendRequest

  val uri = config.getString("uri")
  val writeTo = config.getString("write-to")
  val intervalSec = config.getIntOrElse("interval-sec", 0)

  val handler = new ResponseHandler()
  val client = new AsyncHttpClient()

  var optSchedule: Option[Cancellable] = None

  override def handleMessage = {
    case SendRequest =>
      log.info(f"${name} sending request to ${uri}%20.20s..")
      client.prepareGet(uri).execute(handler)
  }


  //--- system message callbacks
  override def startRaceActor(originator: ActorRef) = {
    super.startRaceActor(originator)

    if (intervalSec > 0) {
      optSchedule = Some(scheduler.schedule(0 seconds, intervalSec seconds,self, SendRequest))
    } else {
      self ! SendRequest
    }
  }

  override def terminateRaceActor (originator: ActorRef) = {
    super.terminateRaceActor(originator)
    ifSome(optSchedule){ _.cancel }
    client.close
  }
}
