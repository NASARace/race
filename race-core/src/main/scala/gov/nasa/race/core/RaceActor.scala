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

import akka.actor._
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race.common
import gov.nasa.race.common.Status._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{InitializeRaceActor, StartRaceActor, _}
import gov.nasa.race.util.ClassLoaderUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.reflect.{ClassTag, classTag}

/**
 * abstract base type for RACE specific actors.
 *
 * RaceActor traits process the RACE specific messages, to support runtime initialization via
 * message (remoting - esp. including making the Bus accessible for remote RaceActors),
 * Bus subscription, simulation start and actor termination
 */
trait RaceActor extends Actor with ImplicitActorLogging {
  // this is the constructor config that has to be provided by the concrete RaceActor
  val config: Config

  val capabilities: RaceActorCapabilities = getCapabilities
  val localRaceContext: RaceContext = RaceActorSystem(system).localRaceContext
  var status = Initializing
  var raceContext: RaceContext = null  // set during InitializeRaceActor
  
  if (supervisor == localMaster) { // means we are a toplevel RaceActor
    info(s"registering toplevel actor $self")
    raceActorSystem.registerActor(self, config, capabilities)
  } else {
    info(s"created second tier actor $self")
  }

  //--- convenience aliases
  @inline final def name = self.path.name
  @inline final def pathString = self.path.toString
  @inline final def system = context.system
  @inline final def scheduler = context.system.scheduler

  @inline final def bus = raceContext.bus
  @inline final def master = raceContext.masterRef

  @inline final def localBus = localRaceContext.bus
  @inline final def localMaster = localRaceContext.masterRef

  @inline final def supervisor = context.parent

  @inline final def busFor(channel: String) = if (channel.startsWith(LOCAL_CHANNEL)) localBus else bus
  @inline final def isLocalChannel (channel: String) = channel.startsWith(LOCAL_CHANNEL)

  @inline final def raceActorSystem = RaceActorSystem(system)
  @inline final def isOptional = config.getBooleanOrElse("optional", false)

  // override if different
  def getCapabilities: RaceActorCapabilities = {
    import RaceActorCapabilities._
    val caps = if (config.getBooleanOrElse("optional", false)) IsOptional else NoCapabilities
    caps + IsAutomatic + SupportsSimTime + SupportsSimTimeReset + SupportsPauseResume
  }

  override def postStop = RaceActorSystem(context.system).stoppedRaceActor(self)

  // pre-init behavior which doesn't branch into concrete actor code before we
  // do the initialization. This guarantees that concrete actor code cannot access
  // un-initialized fields
  override def receive = {
    case RequestRaceActorCapabilities => sender ! capabilities
    case InitializeRaceActor(raceContext,actorConf) => handleInitializeRaceActor(raceContext,actorConf)
    case TerminateRaceActor(originator) => handleTerminateRaceActor(originator)
    //case msg: PingRaceActor => sender ! msg.copy(tReceivedNanos=System.nanoTime())

    case msg => info(f"ignored in pre-init mode: $msg%30.30s..")
  }

  // this switches the message handler to 'receiveLive'
  def handleInitializeRaceActor (rctx: RaceContext, actorConf: Config) = {
    info("got InitializeRaceActor")
    try {
      raceContext = rctx
      context.become(receiveLive)
      if (onInitializeRaceActor(rctx, actorConf)) {
        info("initialized")
        status = Initialized
        sender ! RaceActorInitialized
      } else {
        warning("initialize rejected")
        sender ! RaceActorInitializeFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender ! RaceActorInitializeFailed(ex.getMessage)
    }
  }

  def receiveLive = { // chained message processing
    handleMessage orElse handleSystemMessage
  }

  /**
    * this is the main extension point - override for processing any messages other
    * than Race system messages.
    *
    * NOTE - overrides should *only* handle the messages that have to be processed/ignored. A default
    * ignore clause such as ".. case other => // ignore" will cut off system message processing and
    * cause RACE to automatically terminate upon initialization
    */
  def handleMessage: Receive = {
    case null => // ignore
  }

  def handleSystemMessage: Receive = {
    case InitializeRaceActor(rc,actorConf) => handleLiveInitializeRaceActor(rc, actorConf)
    case StartRaceActor(originator) => handleStartRaceActor(originator)
    case PauseRaceActor(originator) => handlePauseRaceActor(originator)
    case ResumeRaceActor(originator) => handleResumeRaceActor(originator)
    case TerminateRaceActor(originator) => handleTerminateRaceActor(originator)

    case ProcessRaceActor => sender ! RaceActorProcessed
    case msg:PingRaceActor => sender ! msg.copy(tReceivedNanos=System.nanoTime())
    case RequestRaceActorCapabilities => sender ! capabilities

    case rc: ChildNodeRollCall => answerChildNodes(rc)
    case rc: RollCall => rc.answer(self) // generic response

    case SetLogLevel(newLevel) => logLevel = newLevel

    case SyncWithRaceClock => handleSyncWithRaceClock
    case SetTimeout(msg,duration) => context.system.scheduler.scheduleOnce(duration, self, msg)

    case other => warning(s"unhandled system message $other")
  }

  def handleLiveInitializeRaceActor (rc: RaceContext, actorConf: Config) = {
    if (rc != raceContext) {
      info(s"got remote InitializeRaceActor from: ${rc.masterRef.path}")
      try {
        raceContext = rc
        if (onReInitializeRaceActor(rc, actorConf)){
          info("reinitialized")
          sender ! RaceActorInitialized
        } else {
          warning("initialize rejected")
          sender ! RaceActorInitializeFailed("rejected")
        }
      } catch {
        case ex: Throwable => sender ! RaceActorInitializeFailed(ex.getMessage)
      }
    } else {
      // no point re-initializing same context - maybe we should raise an exception
      warning("ignored re-initialization from same context")
      sender ! RaceActorInitialized
    }
  }

  def handleStartRaceActor  (originator: ActorRef) = {
    info(s"got StartRaceActor from: ${originator.path}")
    try {
      val success = if (status == Running) onReStartRaceActor(originator) else onStartRaceActor(originator)
      if (success){
        status = Running
        info("started")
        sender ! RaceActorStarted
      } else {
        warning("start rejected")
        sender ! RaceActorStartFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender ! RaceActorStartFailed(ex.getMessage)
    }
  }

  def handlePauseRaceActor  (originator: ActorRef) = {
    info(s"got PauseRaceActor from: ${originator.path}")
    try {
      val success = onPauseRaceActor(originator)
      if (success){
        status = Paused
        info("paused")
        sender ! RaceActorPaused
      } else {
        warning("pause rejected")
        sender ! RaceActorPauseFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender ! RaceActorPauseFailed(ex.getMessage)
    }
  }

  def handleResumeRaceActor  (originator: ActorRef) = {
    info(s"got ResumeRaceActor from: ${originator.path}")
    try {
      val success = onResumeRaceActor(originator)
      if (success){
        status = Running
        info("resumed")
        sender ! RaceActorResumed
      } else {
        warning("resume rejected")
        sender ! RaceActorResumeFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender ! RaceActorResumeFailed(ex.getMessage)
    }
  }

  def isMandatoryTermination (originator: ActorRef): Boolean = {
    context.parent == originator ||  // parent is always imperative
    RaceActorSystem(system).isTerminating || // whole system is going down
    config.getBooleanOrElse("remote-termination",false)  // remote termination explicitly allowed
  }

  def isLive = status.id < Terminating.id
  def isDone = status.id >= Terminating.id

  def handleTerminateRaceActor (originator: ActorRef) = {
    info(s"got TerminateRaceActor from ${originator.path}")
    if (isMandatoryTermination(originator)){
      try {
        status = Terminating
        if (onTerminateRaceActor(originator)) {
          info("terminated")
          status = common.Status.Terminated
          // note that we don't stop this actor - that is the responsibility of the master/ras
          sender ! RaceActorTerminated
        } else {
          warning("terminate rejected")
          sender ! RaceActorTerminateFailed("rejected")
        }
      } catch {
        case x: Throwable =>
          x.printStackTrace()
          sender ! RaceActorTerminateFailed(x.toString)
      }
    } else {
      info("ignored remote TerminateRaceActor")
      sender ! RaceActorTerminateIgnored
    }
  }

  def handleSyncWithRaceClock = {
    if (onSyncWithRaceClock) {
      info("clock synced")
    } else {
      warning("clock sync rejected")
    }
  }

  //--- the general system message callbacks

  // note that RaceActor itself does not depend on overrides properly calling super.onXX(), all the
  // critical system processing we do in the non-overridden handleXX() that call the overridable onXX()

  // the return value determines if the corresponding handleXMessage send an acknowledge or
  // a rejection

  // Note also that there are no separate ReXX messages, calling onReXX() is just done on the basis of the current
  // RaceActor state (we might support re-initialization in local RAS in the future)

  def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = true
  def onReInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = true

  def onStartRaceActor(originator: ActorRef): Boolean = true
  def onReStartRaceActor(originator: ActorRef): Boolean = true

  def onPauseRaceActor(originator: ActorRef): Boolean = true
  def onResumeRaceActor(originator: ActorRef): Boolean = true

  def onTerminateRaceActor(originator: ActorRef): Boolean = true

  def onSyncWithRaceClock: Boolean = true

  def answerChildNodes (rc: ChildNodeRollCall) = {
    rc.answer(self -> ChildNode(self,Set.empty))
  }

  def loadClass[T] (clsName: String, clsType: Class[T]): Class[_ <:T] = {
    ClassLoaderUtils.loadClass(context.system, clsName, clsType)
  }
  def newInstance[T: ClassTag](clsName: String,
                               argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T] = {
    ClassLoaderUtils.newInstance( context.system, clsName, argTypes, args)
  }

  // NOTE: apparently Scala 2.12.x does not infer the type parameter from the call context, which can lead
  // to runtime exceptions. Specify the generic type explicitly!

  def configurable[T: ClassTag](conf: Config): Option[T] = {
    val clsName = conf.getString("class")
    info(s"instantiating $clsName")
    newInstance[T](clsName, Array(classOf[Config]), Array(conf))
  }

  /**
    * try to instantiate the requested type from a sub-config that includes a 'class' setting
    * if there is no sub-config but a string, use that as class name
    * if there is no sub-config or string, fallback to arg-less instantiation of the requested type
    *
    * note - see above comments reg. inferred type parameter
    */
  def configurable[T: ClassTag](key: String): Option[T] = {
    try {
      config.getOptionalConfig(key) match {
        case Some(conf) =>
          newInstance(conf.getString("class"),Array(classOf[Config]),Array(conf))

        case None => // no entry for this key
          // arg-less ctor is just a fallback - this will fail if the requested type is abstract
          Some(classTag[T].runtimeClass.newInstance.asInstanceOf[T])
      }
    } catch {
      case _: ConfigException.WrongType => // we have an entry but it is not a Config
        // try if the entry is a string and use it as class name to instantiate, pass in actor config as arg
        // (this supports old-style, non-sub-config format)
        newInstance(config.getString(key), Array(classOf[Config]), Array(config))

      case _: Throwable => None
    }
  }
  /** use this if the instantiation is mandatory */
  def getConfigurable[T: ClassTag](key: String): T = configurable(key).get
  def getConfigurableOrElse[T: ClassTag](key: String)(f: => T): T = configurable(key).getOrElse(f)

  def getConfigurables[T: ClassTag](key: String): Array[T] = {
    config.getConfigArray(key).map( conf=> newInstance(conf.getString("class"),Array(classOf[Config]),Array(conf)).get)
  }

  def instantiateActor (actorName: String, actorConfig: Config): ActorRef = {
    val clsName = actorConfig.getClassName("class")
    val actorCls = loadClass(clsName, classOf[RaceActor])
    try {
      actorCls.getConstructor(classOf[Config])
      context.actorOf(Props(actorCls, actorConfig), actorName)
    } catch {
      case _: java.lang.NoSuchMethodException =>
        actorCls.getConstructor()
        context.actorOf(Props(actorCls), actorName)
    }
  }

  def getUniverseConfigOrElse (key: String, f: => Config): Config = {
    RaceActorSystem(system).config.getOptionalConfig(key) match {
      case Some(config) => config
      case None => f
    }
  }

  def isLocalContext (rc: RaceContext) = rc.bus eq localBus

  def commitSuicide (errMsg: String) = {
    if (errMsg != null) error(errMsg)
    self ! PoisonPill
  }

  def failDuringConstruction (errMsg: String) = {
    error(errMsg)
    throw new RuntimeException(s"constructor failed: $errMsg")
  }

  def scheduleNow (interval: FiniteDuration, msg: Any) : Option[Cancellable] = {
    Some(scheduler.schedule(0.seconds, interval, self, msg))
  }

  def scheduleOnce (dur: FiniteDuration)(f: =>Unit) = scheduler.scheduleOnce(dur)(f)

  def delay (d: FiniteDuration, action: ()=>Unit): Option[Cancellable] = Some(scheduler.scheduleOnce(d,self,DelayedAction(self,action)))


  //--- timeout values we might need during actor initialization in order to clean up
  protected def _getTimeout(key: String) = config.getFiniteDurationOrElse(key,raceActorSystem.defaultActorTimeout)
  def createTimeout = _getTimeout("create-timeout")
  def initTimeout = _getTimeout("init-timeout")
  def startTimeout = _getTimeout("start-timeout")

  //--- per actor configurable logging

  // note that we effectively bypass the Akka LoggingAdapter here since we want to use our own configuration
  // Unfortunately, Akka does not easily support per-actor log levels.
  // This might cause unwanted log events in case Akka system classes do the same trick, bypassing the LoggingAdapter and
  // directly publishing to the event stream.
  // note also that per-actor logging only works if the RaceActor APIs are used, not the LoggingAdapter (which does
  // filtering on its own)
  // TODO - this is brittle, it depends on Akka's logging internals and our own logger configuration (see RaceLogger)
  import akka.event.Logging._

  var logLevel: LogLevel = RaceLogger.getConfigLogLevel(system, config.getOptionalString("loglevel")).
    getOrElse(RaceActorSystem(system).logLevel)

  @inline final def isLoggingEnabled (testLevel: LogLevel) = testLevel <= logLevel

  @inline final def debug(msg: => String): Unit = if (DebugLevel <= logLevel) system.eventStream.publish(Debug(pathString,getClass,msg))
  @inline final def info(msg: => String): Unit = if (InfoLevel <= logLevel) system.eventStream.publish(Info(pathString,getClass,msg))
  @inline final def warning(msg: => String): Unit = if (WarningLevel <= logLevel) system.eventStream.publish(Warning(pathString,getClass,msg))
  @inline final def error(msg: => String): Unit = if (ErrorLevel <= logLevel) system.eventStream.publish(Error(pathString,getClass,msg))
}












