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
package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent

import scala.annotation.tailrec

/**
  * a router that uses configurable types to determine the target channels
  *
  *   routes = [
  *     {type="x.z.Z"; write-to: ["chan-Z"]}, ...
  *   ]
  */
class TypeRouter (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  case class TypeRoute(routeCls: Class[_], channels: Array[String])

  val routes = config.getConfigArray("routes").map { rc =>
    TypeRoute(loadClass(rc.getString("type"), classOf[Any]), rc.getStringArray("write-to"))
  }
  val defaultRoute = config.getOptionalString("default-route")

  override def handleMessage = {
    case BusEvent(readFrom, msg, originator) => checkRoutes(msg)
  }

  def checkRoutes(msg: Any) = {
    val msgCls = msg.getClass
    @tailrec def _checkRoute(i: Int): Unit = {
      if (i < routes.length){
        val tr = routes(i)
        if (tr.routeCls.isAssignableFrom(msgCls)) {
          tr.channels.foreach( publish(_,msg))
        } else {
          _checkRoute(i+1)
        }
      } else {
        ifSome(defaultRoute){ publish(_,msg) }
      }
    }

    if (routes.nonEmpty) _checkRoute(0)
  }
}