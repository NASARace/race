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

package gov.nasa.race.actors.viewers

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common.ConfigurableTranslator
import gov.nasa.race.core.{BusEvent, SubscribingRaceActor}


/**
 * utility actor that prints messages received on its configured 'read-from' channels to stdout
 * (i.e. it prints in the terminal the process is running in, which might be a satellite)
 */
class ProbeActor (val config: Config) extends SubscribingRaceActor {
  val prefix = config.getOptionalString("prefix")
  val translator: Option[ConfigurableTranslator] = config.getOptionalConfig("translator") flatMap createTranslator

  override def handleMessage = {
    case BusEvent(sel,msg,_) => report(sel,msg)
  }

  def createTranslator(transConf: Config) = {
    newInstance[ConfigurableTranslator](transConf.getString("class"), Array(classOf[Config]), Array(transConf))
  }

  def report (channel: String, msg: Any): Unit = {
    val o = if (translator.isDefined){
      translator.get.translate(msg) match {
        case Some(x) => x
        case None => return // no translation, no output
      }
    } else msg

    if (prefix.isDefined){
      println(s"${prefix.get}$o")
    } else {
      println(s"got on channel: '$channel' message: '$o'")
    }
  }
}
