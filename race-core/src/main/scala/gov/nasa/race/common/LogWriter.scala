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

/**
  * something that can produce info/warning/error log messages but does not know the logging context (console, actor etc)
  */
trait LogWriter {

  // the input is a by-name parameter so that we can override with something that can use
  // log levels to determine if the message string should be generated
  type LogFunc = (=>String)=>Unit

  protected var info: LogFunc    = msg => println(s"INFO: $msg")
  protected var warning: LogFunc = msg => println(s"WARN: $msg")
  protected var error: LogFunc   = msg => println(s"ERROR: $msg")

  def setLogging (infoLogger: LogFunc, warningLogger: LogFunc, errorLogger: LogFunc): Unit = {
    info = infoLogger
    warning = warningLogger
    error = errorLogger
  }
}

/**
  * something that can throw a specific exception, which might depend on its context
  */
trait Thrower {

  protected def exception (msg: String): Throwable = new RuntimeException(msg)
}