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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.DDSReader
import gov.nasa.race.common._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core.{PublishingRaceActor, RaceContext}

/**
  * actor that imports data from DDS
  *
  * as with the DDSExportActor, we support ad hoc translation to save context switches
  * in case we do not directly work with the (IDL generated) DDS types
  */
class DDSImportActor (val config: Config) extends PublishingRaceActor {

  var ddsReader: Option[DDSReader[_]] = None // defer init, topic might be from remote config
  val translator: Option[ConfigurableTranslator] = config.getOptionalConfig("translator") flatMap createTranslator

  val thread = ThreadUtils.daemon {
    ifSome(ddsReader) { reader =>
      val processSample: Any=>Unit = if (translator.isDefined) publishTranslated(translator.get) _ else publish
      forever {
        reader.read(processSample)
      }
    }
  }

  def createTranslator(transConf: Config) = newInstance[ConfigurableTranslator](transConf.getString("class"), Array(classOf[Config]), Array(transConf))
  def createReader(readerConf: Config) = newInstance[DDSReader[_]](readerConf.getString("class"), Array(classOf[Config]),Array(readerConf))
  def publishTranslated (t:ConfigurableTranslator)(o: Any) = t.translate(o).foreach(publish)

  //--- RaceActor callbacks

  override def onInitializeRaceActor (raceContext: RaceContext, actorConf: Config) = {
    ddsReader = createReader(actorConf.getConfig("reader"))
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    if (ddsReader.isDefined) thread.start
  }

  override def onTerminateRaceActor (originator: ActorRef) = {
    ifSome(ddsReader){ _.close }
    super.onTerminateRaceActor(originator)
  }
}
