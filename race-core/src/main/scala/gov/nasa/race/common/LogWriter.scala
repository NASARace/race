/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.common

import gov.nasa.race.core.Loggable

/**
  * something that can produce info/warning/error log messages but does not know the logging context (console, actor etc)
  */
trait LogWriter {

  // the input is a by-name parameter so that we can override with something that can use
  // log levels to determine if the message string should be generated
  type LogFunc = (=>String)=>Unit

  // change is protected
  protected var _info: LogFunc    = msg => println(s"INFO: $msg")
  protected var _warning: LogFunc = msg => println(s"WARN: $msg")
  protected var _error: LogFunc   = msg => println(s"ERROR: $msg")

  def setLogging (infoLogger: LogFunc, warningLogger: LogFunc, errorLogger: LogFunc): Unit = {
    _info = infoLogger
    _warning = warningLogger
    _error = errorLogger
  }

  def setLogging (logger: Loggable): Unit = {
    _info = logger.info
    _warning = logger.warning
    _error = logger.error
  }

  // invocation is public
  def info (f: =>String): Unit = _info(f)
  def warning(f: =>String): Unit = _warning(f)
  def error(f: =>String): Unit = _error(f)
}

/**
  * something that can throw a specific exception, which might depend on its context
  */
trait Thrower {

  protected def exception (msg: String): Throwable = new RuntimeException(msg)
}