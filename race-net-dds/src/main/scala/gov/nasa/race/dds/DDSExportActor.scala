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

package gov.nasa.race.dds

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableTranslator
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{RaceContext, SubscribingRaceActor}

/**
  * actor that publishes to a DDS topic
  *
  * since DDS types are usually generated from IDL and hence cannot be extended (no methods), we support embedded
  * translation to save context switches at runtime
  *
  * Note that the concrete DDSWriter class can also do ad hoc translation, but in addition we support a
  * optional translator so that there is no need to modify the DDSWriter if translations are added
  * after-the-fact (e.g. when adding new non-DDS types)
  */
class DDSExportActor (val config: Config) extends SubscribingRaceActor {
  var ddsWriter: Option[DDSWriter[_]] = None // defer init, topic might be from remote config
  val translator: Option[ConfigurableTranslator] = config.getOptionalConfig("translator") flatMap createTranslator

  def createTranslator(transConf: Config) = newInstance[ConfigurableTranslator](transConf.getString("class"), Array(classOf[Config]), Array(transConf))
  def createWriter(writerConf: Config) = newInstance[DDSWriter[_]](writerConf.getString("class"), Array(classOf[Config]),Array(writerConf))

  //--- RaceActor callbacks
  override def onInitializeRaceActor (raceContext: RaceContext, actorConf: Config) = {
    ddsWriter = createWriter(actorConf.getConfig("writer"))
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  override def onTerminateRaceActor (originator: ActorRef) = {
    ifSome(ddsWriter){ _.close }
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case BusEvent(_,msg,_) => ifSome(ddsWriter) { writer =>
      if (translator.isDefined) {
        translator.get.translate(msg) match {
          case Some(fr) => writer.write(fr)
          case None => // ignored
        }
      } else writer.write(msg)
    }
  }
}
