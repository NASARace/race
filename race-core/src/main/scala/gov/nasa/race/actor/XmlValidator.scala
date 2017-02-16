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

import java.io.File

import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import com.typesafe.config.Config
import gov.nasa.race.common.{AnnotatedItem, XmlValidationFilter}

/**
  * an actor that validates XML messages with a configured schema
  *
  * note that this is a FilteringPublisher, i.e. we can explicitly configure pre-filters, and only
  * if those pass do we check our automatically added XmlValidationFilter
  *
  * this being a EitherOrRouter, messages that validate are written to 'write-to-pass' and the ones
  * that fail to 'write-to-fail'. In addition, if we have an optional 'write-to-log' configured, we publish
  * a AnnotatedItem to it that also preserves the error. This can be used to debug validation errors
  *
  * to obtain fail statistics, use a XmlMsgStatsCollector that reads from 'write-to-fail'
  */
class XmlValidator (config: Config) extends EitherOrRouter(config) {

  val schemaFile = new File(config.getString("schema"))
  val validationFilter = new XmlValidationFilter(schemaFile)
  val writeToLog = config.getOptionalString("write-to-log")

  override def defaultMatchAll = true // the first filter that doesn't pass shortcuts

  override def action (msg: Any, isPassing: Boolean) = {
    if (isPassing){
      if (validationFilter.pass(msg)) {
        publish(writeToPass, msg) // it validates, pass it on as-is

      } else {
        publish(writeToFail, msg)

        ifSome(writeToLog) { chan =>
          // we need to wrap this into a AnnotatedItem to preserve the validation error
          publish(chan, new AnnotatedItem(validationFilter.lastError, msg))
        }
      }
    }
  }
}
