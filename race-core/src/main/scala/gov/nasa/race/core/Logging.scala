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

import java.io.{PrintStream, PrintWriter}
import java.net.Socket
import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.dispatch.RequiresMessageQueue
import akka.event.LoggerMessageQueueSemantics
import akka.event.Logging._
import com.typesafe.config.Config
import gov.nasa.race.common.{ClientSocketPrinter, ServerSocketPrinter}
import gov.nasa.race.util.ConsoleIO

/**
  * trait to format Akka log events (as defined in akka.event.Logging.{Error,Warning,Info,Debug}
  */
trait RaceLogFormatter {
  //--- the default implementations
  def format (e: Error): String   = s"\n[ERR]  ${e.logSource}: ${e.message}"
  def format (e: Warning): String = s"\n[WARN] ${e.logSource}: ${e.message}"
  def format (e: Info): String    = s"\n[INFO] ${e.logSource}: ${e.message}"
  def format (e: Debug): String   = s"\n[DBG]  ${e.logSource}: ${e.message}"
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

/**
  * RACE's own log interface
  * This can bypass the normal Akka logging pipeline if the RaceLogger is configured as the Akka logger (which we
  * do as a default) since RaceActors have their own logging API, to support single source, per-actor configuration
  *
  * The normal Akka logging pipeline is like this (->: call, =>: send)
  *
  *                                         Akka EventStream
  *                                  creates            subscribes
  *   client "log.info(..)" -> Akka LoggingAdapter => Akka log actor -> log backend
  *                              LoggingFilter
  *   config:                       'akka.loglevel'     'akka.loggers'       backend config (e.g. logback.xml)
  *                                 [level only]          [class]            [whatever, not a *.conf]
  *
  * The normal way is to configure a generic Akka Slf4jLogger log actor which uses logback as the backend, but this
  * does not work for us because
  *
  *   - the LoggingAdapter does not support per-actor log levels (requires filtering in LoggingAdapter *and*
  *     level subscriptions for log actor)
  *   - the backend usually does serialization of concurrent log entries, which we already get from the log actor
  *   - we want to be able to intercept log *events* so that we don't have to reparse output, e.g. for runtime monitoring
  *   - we want to avoid separate configuration files (akka HOCON, logback XML, ..)
  *   - without our own log processing there is no guarantee we would end up with a single backend anyways
  *     (there might be different backends used from different libraries). To avoid this, we have to configure those
  *     backends so that they send to our log actor. Log aggregation should be in our realm, not in the backends
  *
  *  The bottom line is that standard backends cost us more than they provide. It is more important for RACE to be able
  *  to easily extend the pipeline with consistent, single source configuration
  *
  *  TODO - re-evaluate if Akka provides new APIs for setting LoggingAdapter and log actor
  */
object RaceLogger {
  /**  the producer -> output interface, can be set prior to starting a RaceActorSystem */
  var logAppender: LogAppender = createDefaultLogAppender

  /** the output -> producer interface (used by RACE drivers that want to set log levels programmatically */
  var logController: LogController = new PseudoLogController

  /*
   * per-actor configured log level support. Note this only works for RaceLogger because we have to obtain the logger
   * instance (there are no Akka APIs for this). It is not enough to bypass the Akka LoggingAdapter/LogFilter since we
   * also need to subscribe the RaceLogger for the required log level (classifier is the log event class, e.g. Info).
   *
   * TODO - this is way too much Akka bypassing
   */
  private var logActor: ActorRef = _


  def createDefaultLogAppender: LogAppender = new ConsoleAppender

  def getConfigLogLevel (sys: ActorSystem, optLevel: Option[String]): Option[LogLevel] = {
    val logBus = sys.eventStream
    if (logActor != null) {
      optLevel match {
          // NOTE - we might subscribe here to more log levels than what the LoggingAdapter knows about. If something
          // (e.g. in Akka) directly publishes log events to the bus that would normally go un-noticed because there
          // was no subscription, our logActor would see and report them. As of Akka 2.4.8 this only happens during
          // ActorSystem termination from the EventStream itself, which we catch by explicitly terminating RaceLogger
        case Some(levelName) => levelName.toLowerCase match {
          case "error"   =>
            logBus.subscribe(logActor,classOf[Error])
            Some(ErrorLevel)
          case "warning" =>
            logBus.subscribe(logActor,classOf[Error])
            logBus.subscribe(logActor,classOf[Warning])
            Some(WarningLevel)
          case "info"    =>
            logBus.subscribe(logActor,classOf[Error])
            logBus.subscribe(logActor,classOf[Warning])
            logBus.subscribe(logActor,classOf[Info])
            Some(InfoLevel)
          case "debug"   =>
            logBus.subscribe(logActor,classOf[Error])
            logBus.subscribe(logActor,classOf[Warning])
            logBus.subscribe(logActor,classOf[Info])
            logBus.subscribe(logActor,classOf[Debug])
            Some(DebugLevel)
          case other => None
        }
        case None => None
      }
    } else None
  }

  def terminate (sys: ActorSystem) = {
    if (logActor != null){
      val logBus = sys.eventStream
      logBus.unsubscribe(logActor,classOf[Warning])
      logBus.unsubscribe(logActor,classOf[Info])
      logBus.unsubscribe(logActor,classOf[Debug])
      logAppender.appendInfo("RACE logging terminated")
    }

    logAppender.terminate
  }
}
import gov.nasa.race.core.RaceLogger._

/**
  * a minimal Akka Log actor that uses our logAppender, i.e. does not require any external logger backend such as Logback
  *
  * This is instantiated by Akka, hence we cannot use a normal singleton
  */
class RaceLogger extends Actor with RequiresMessageQueue[LoggerMessageQueueSemantics] with RaceLogFormatter {
  RaceLogger.logActor = self

  def receive = {
    case e: Error   => logAppender.appendError(format(e))
    case e: Warning => logAppender.appendWarning(format(e))
    case e: Info    => logAppender.appendInfo(format(e))
    case e: Debug   => logAppender.appendDebug(format(e))

    case InitializeLogger(_) =>
      logAppender.appendInfo("RACE logging initialized")
      sender() ! LoggerInitialized

    case other => // ignore
  }
}

/**
  * abstract log message formatter base type
  */
trait LogAppender {
  def appendError (s: String): Unit
  def appendWarning (s: String): Unit
  def appendInfo (s: String): Unit
  def appendDebug (s: String): Unit

  def terminate: Unit = {}
}

/**
  * a very barebones log formatter that just prints messages to stdout/err
  */
class StdioAppender extends LogAppender {
  override def appendError (s: String) = System.err.println(s)
  override def appendWarning (s: String) = System.err.println(s)
  override def appendInfo (s: String) = System.out.println(s)
  override def appendDebug (s: String) = System.out.println(s)
}

class PrintWriterAppender (out: PrintWriter) extends LogAppender {
  override def appendError (s: String) = out.println(s)
  override def appendWarning (s: String) = out.println(s)
  override def appendInfo (s: String) = out.println(s)
  override def appendDebug (s: String) = out.println(s)
}

object ConsoleAppender {
  val defaultPort = 2551
  val defaultHost = "localhost"

  def getLoggingHost: String = {
    val hostSpec = System.getProperty("log.host")
    if (hostSpec != null) hostSpec else defaultHost
  }

  def getLoggingPort: Int = {
    val portSpec = System.getProperty("log.port") // we don't have a config yet, only Java system properties
    if (portSpec != null){
      try {
        Integer.parseInt(portSpec)
      } catch {
        case _:Throwable =>
          System.err.println(s"invalid log.port specification '$portSpec', falling back to $defaultPort")
          defaultPort
      }
    } else defaultPort
  }
}

/**
  * appender that writes synchronized to a stream which can be configured to write to a server port
  */
class ConsoleAppender extends ClientSocketPrinter("consoleLogging",
                                                  ConsoleAppender.getLoggingHost,
                                                  ConsoleAppender.getLoggingPort,
                                                  SystemLoggable) with LogAppender {

  private def printColoredOn(ps: PrintStream, s: String, ansiClr: String): Unit = {
    synchronized {
      ps.print(ansiClr)
      ps.println(s)
      ps.println(scala.Console.RESET)
    }
  }

  //--- LogAppender interface

  override def appendError(s: String): Unit = withPrintStreamOrElse(System.out)( printColoredOn(_,s,scala.Console.RED))
  override def appendWarning(s: String): Unit = withPrintStreamOrElse(System.out)( printColoredOn(_,s,scala.Console.YELLOW))
  override def appendInfo(s: String): Unit = withPrintStreamOrElse(System.out)( printColoredOn(_,s,scala.Console.RESET))
  override def appendDebug(s: String): Unit = withPrintStreamOrElse(System.out)( printColoredOn(_,s,scala.Console.MAGENTA))

  override def terminate: Unit = {
    withPrintStreamOrElse(System.out) { ps =>
      ps.print(scala.Console.RESET)
      ps.flush
    }
    super.terminate
  }
}

/**
  * something that can log messages in 4 different levels
  */
trait Loggable {
  def debug(f: => String): Unit
  def info(f: => String): Unit
  def warning(f: => String): Unit
  def error(f: => String): Unit
}

/**
  * if we need a bootstrap Loggable without config
  */
object SystemLoggable extends Loggable {
  def debug(f: => String): Unit = {} // ignored
  def info(f: => String): Unit = System.out.println(f)
  def warning(f: => String): Unit = System.err.println(f)
  def error(f: => String): Unit = System.err.println(f)
}

/**
  * a Loggable that can be configured
  *
  * note that we effectively bypass the Akka LoggingAdapter here since we want to use our own configuration
  * Unfortunately, Akka does not easily support per-actor log levels.
  * This might cause unwanted log events in case Akka system classes do the same trick, bypassing the LoggingAdapter and
  * directly publishing to the event stream.
  *
  * note also that per-actor logging only works if the RaceActor APIs are used, not the LoggingAdapter (which does
  * filtering on its own)
  *
  * TODO - this is brittle, it depends on Akka's logging internals and our own logger configuration (see RaceLogger)
  */
trait ConfigLoggable extends Loggable {
  import akka.event.Logging._
  import gov.nasa.race.config.ConfigUtils._

  def config: Config
  def system: ActorSystem
  def name: String

  var logLevel: LogLevel = RaceLogger.getConfigLogLevel(system, config.getOptionalString("loglevel")).
    getOrElse(RaceActorSystem(system).logLevel)

  @inline final def isLoggingEnabled (testLevel: LogLevel) = testLevel <= logLevel

  // note also this is somewhat redundant to the LogClient trait but we need a per-actor log level, not per-adapter

  @inline final def debug(msg: => String): Unit = if (DebugLevel <= logLevel) system.eventStream.publish(Debug(name,getClass,msg))
  @inline final def info(msg: => String): Unit = if (InfoLevel <= logLevel) system.eventStream.publish(Info(name,getClass,msg))
  @inline final def warning(msg: => String): Unit = if (WarningLevel <= logLevel) system.eventStream.publish(Warning(name,getClass,msg))
  @inline final def error(msg: => String): Unit = if (ErrorLevel <= logLevel) system.eventStream.publish(Error(name,getClass,msg))
}