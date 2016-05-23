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

package gov.nasa.race.core

import akka.actor.Actor
import akka.dispatch.RequiresMessageQueue
import akka.event.LoggerMessageQueueSemantics
import akka.event.Logging._
import gov.nasa.race.common.{LogAppender,StdioAppender}

/**
  * trait to format Akka log events (as defined in akka.event.Logging.{Error,Warning,Info,Debug}
  */
trait RaceLogFormatter {
  //--- the default implementations
  def format (e: Error): String   = s"[ERR]  ${e.logSource}: ${e.message}"
  def format (e: Warning): String = s"[WARN] ${e.logSource}: ${e.message}"
  def format (e: Info): String    = s"[INFO] ${e.logSource}: ${e.message}"
  def format (e: Debug): String   = s"[DBG]  ${e.logSource}: ${e.message}"
}


trait LogController {
  def logLevel: LogLevel
  def setLogLevel (logLevel: LogLevel): Unit
}

class PseudoLogController extends LogController {
  var level: LogLevel = ErrorLevel
  override def logLevel = level
  override def setLogLevel (newLogLevel: LogLevel) = level = newLogLevel
}

object RaceLogger {
  /**  the producer -> output interface, can be set prior to starting a RaceActorSystem */
  var logAppender: LogAppender = new StdioAppender

  /** the output -> producer interface */
  var logController: LogController = new PseudoLogController
}
import RaceLogger._

/**
  * a minimal Akka Log actor that prints messages to the standard System.{out,err}
  * streams without the need to configure any external logger backend such as Logback
  */
class RaceLogger extends Actor with RequiresMessageQueue[LoggerMessageQueueSemantics] with RaceLogFormatter {
  def receive = {
    case e: Error   => logAppender.appendError(format(e))
    case e: Warning => logAppender.appendWarning(format(e))
    case e: Info    => logAppender.appendInfo(format(e))
    case e: Debug   => logAppender.appendDebug(format(e))
    case InitializeLogger(_) ⇒
      logAppender.appendInfo("RACE logging initialized")
      sender() ! LoggerInitialized
  }
}
