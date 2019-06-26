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

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.XmlValidationFilter
import gov.nasa.race.config.ConfigUtils._

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

  val schemaPaths = config.getStringSeq("schemas")
  val validationFilter = new XmlValidationFilter(schemaPaths.map(new File(_)))

  ifSome(validationFilter.lastError){ err=>
    error(s"schema did not parse: $err")
  }

  val failurePrefix = config.getOptionalString("failure-prefix")
  val failurePostfix = config.getOptionalString("failure-postfix")

  override def defaultMatchAll = true // the first filter that doesn't pass shortcuts

  override def action (msg: Any, isPassing: Boolean) = {
    if (isPassing){
      if (validationFilter.pass(msg)) {
        publish(writeToPass, msg) // it validates, pass it on as-is

      } else {
        val lastErr = validationFilter.lastError.getOrElse("?")
        info(s"XML validation failed: $lastErr")

        val failMsg = if (failurePrefix.isDefined || failurePostfix.isDefined) {
          failurePrefix.getOrElse("") + lastErr + failurePostfix.getOrElse("") + msg
        } else msg // don't allocate if you don't have to, msg might be large

        publish(writeToFail, failMsg)
      }
    }
  }
}
