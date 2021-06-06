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

package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.Translator
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.SubscribingRaceActor
import gov.nasa.race.config.ConfigUtils._

/**
 * a generic actor that translates text messages into objects by means of a
 * configured Translator instance
 */
class TranslatorActor (val config: Config) extends SubscribingRaceActor with FilteringPublisher {

  var translator: Translator[Any,Any] = createTranslator
  val flatten: Boolean = config.getBooleanOrElse("flatten", true)

  /** override this to use a hardwired translator */
  protected def createTranslator: Translator[Any,Any] = getConfigurable[Translator[Any,Any]]("translator")

  /** we provide our own PF so that derived classes can delegate from their handleMessage */
  def handleTranslatorMessage: Receive = {
    case BusEvent(_,msg,_) => translateAndPublish(msg)
  }

  override def handleMessage: Receive = handleTranslatorMessage

  def translateAndPublish (msg: Any) = {
    translator.translate(msg) match {
      case Some(it:Iterable[_]) =>
        if (flatten) {
          it.foreach(processTranslationProduct)
        } else {
          processTranslationProduct(it)
        }
      case Some(o) => processTranslationProduct(o)
      case None => // ignore
    }
  }

  /** can be overridden by specialized translator actors */
  def processTranslationProduct(o: Any) = publishFiltered(o)
}
