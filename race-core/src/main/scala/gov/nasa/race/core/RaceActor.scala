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
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race._
import gov.nasa.race.common
import gov.nasa.race.common.Status._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.{NamedConfigurable, SubConfigurable}
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
trait RaceActor extends Actor with ImplicitActorLogging with NamedConfigurable with SubConfigurable with ConfigLoggable {
  // this is the constructor config that has to be provided by the concrete RaceActor
  val config: Config

  var capabilities: RaceActorCapabilities = getCapabilities
  val localRaceContext: RaceContext = RaceActorSystem(system).localRaceContext
  var status = Initializing
  var raceContext: RaceContext = null  // set during InitializeRaceActor
  var nMsgs: Long = 0 // processed live messages

  // invariants that might cause allocation
  override val name = config.getStringOrElse("name", self.path.name)
  val level = self.path.elements.size - 2 // count out /user and master

  //--- convenience aliases
  @inline final def system: ActorSystem = context.system
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

  @inline final def timeScale: Double = raceActorSystem.timeScale

  override def clAnchor: Any = system // override SubConfigurable to use the ActorSystem as the ClassLoader anchor object

  // this is called in response to RaceActorInitialize
  def getCapabilities: RaceActorCapabilities = {
    import RaceActorCapabilities._
    val caps = if (config.getBooleanOrElse("optional", false)) IsOptional else NoCapabilities
    caps + IsAutomatic + SupportsSimTime + SupportsSimTimeReset + SupportsPauseResume
  }

  def getRaceActorRef (actorName: String): ActorRef = raceActorSystem.getRaceActorRef(name)
  def getOptionalRaceActorRef (actorName: String): Option[ActorRef] = raceActorSystem.getOptionalRaceActorRef(name)

  override def postStop() = supervisor ! RaceActorStopped

  // pre-init behavior which doesn't branch into concrete actor code before we
  // do the initialization. This guarantees that concrete actor code cannot access
  // un-initialized fields
  override def receive: Receive = {
    case PingRaceActor(sentNanos: Long, statsCollector: ActorRef) => handlePingRaceActor(sender(),sentNanos,statsCollector)
    case ShowRaceActor(sentNanos: Long) => handleShowRaceActor(sender(),sentNanos)
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
      context.become(receiveLive, true)
      if (onInitializeRaceActor(rctx, actorConf)) {
        info("initialized")
        status = Initialized
        sender() ! RaceActorInitialized(capabilities)
      } else {
        warning("initialize rejected")
        sender() ! RaceActorInitializeFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender() ! RaceActorInitializeFailed(ex.getMessage)
    }
  }

  protected var userMessageHandler: Receive = handleMessage

  /**
    * this corresponds to the low level Akka become() but just for RACE user messages
    */
  protected def swapUserMessageHandler (newHandler: Receive): Unit = {
    info("swapping user message handler")
    userMessageHandler = newHandler
  }

  /**
    * this should not be overridden to ensure consistent user- and system-message handling
    */
  def receiveLive: Receive = { // chained message processing
    (userMessageHandler orElse handleSystemMessage) andThen(_ => nMsgs += 1)
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

  // NOTE - make sure we don't match on case class companion objects - the compiler would not warn!
  def handleSystemMessage: Receive = {
    case InitializeRaceActor(rc,actorConf) => handleLiveInitializeRaceActor(rc, actorConf)
    case StartRaceActor(originator) => handleStartRaceActor(originator)
    case PauseRaceActor(originator) => handlePauseRaceActor(originator)
    case ResumeRaceActor(originator) => handleResumeRaceActor(originator)
    case TerminateRaceActor(originator) => handleTerminateRaceActor(originator)

    case ProcessRaceActor => sender() ! RaceActorProcessed
    case PingRaceActor(sentNanos,statsCollector) => handlePingRaceActor(sender(),sentNanos,statsCollector)

    case ShowRaceActor(sentNanos) => handleShowRaceActor(sender(),sentNanos) // response processed sync

    case SetLogLevel(newLevel) => logLevel = newLevel

    case SyncWithRaceClock => handleSyncWithRaceClock
    case SetTimeout(msg,duration) => context.system.scheduler.scheduleOnce(duration, self, msg)

    case RacePaused => handleRacePaused(sender()) // master reply for a RacePauseRequest (only the requester gets this)
    case RacePauseFailed(details: Any) => handleRacePauseFailed(sender(),details)

    case RaceResumed => handleRaceResumed(sender())
    case RaceResumeFailed(details: Any) => handleRaceResumeFailed(sender(),details)

      // this is an exception to the rule that system messages are sent directly and not through configured channels
      // we use a BusEvent since the originator does not know the identities of potential responders and the main purpose
      // is to make sure that whatever the originator published before has been processed
    case BusEvent(_, req: SyncRequest, _) => handleSyncRequest(req)
    case SyncResponse(responder, responderType, request) => handleSyncResponse(responder, responderType, request)

    case other => warning(s"unhandled system message $other")
  }

  def handleLiveInitializeRaceActor (rc: RaceContext, actorConf: Config) = {
    if (rc != raceContext) {
      info(s"got remote InitializeRaceActor from: ${rc.masterRef.path}")
      try {
        raceContext = rc
        if (onReInitializeRaceActor(rc, actorConf)){
          info("reinitialized")
          sender() ! RaceActorInitialized(capabilities)
        } else {
          warning("initialize rejected")
          sender() ! RaceActorInitializeFailed("rejected")
        }
      } catch {
        case ex: Throwable => sender() ! RaceActorInitializeFailed(ex.getMessage)
      }
    } else {
      // no point re-initializing same context - maybe we should raise an exception
      warning("ignored re-initialization from same context")
      sender() ! RaceActorInitialized(capabilities)
    }
  }

  def handleStartRaceActor  (originator: ActorRef) = {
    info(s"got StartRaceActor from: ${originator.path}")
    try {
      val success = if (status == Running) onReStartRaceActor(originator) else onStartRaceActor(originator)
      if (success){
        status = Running
        info("started")
        sender() ! RaceActorStarted
      } else {
        warning("start rejected")
        sender() ! RaceActorStartFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender() ! RaceActorStartFailed(ex.getMessage)
    }
  }

  def handlePingRaceActor (originator: ActorRef, sentNanos: Long, statsCollector: ActorRef) = {
    val now = System.nanoTime
    val response = PingRaceActorResponse(now, now - sentNanos, nMsgs)

    originator ! response // this is for liveness control (unless originator is also collector)
    if ((statsCollector ne ActorRef.noSender) && (statsCollector ne originator)) {
      statsCollector ! response // this is for statistics purposes
    }
  }

  def showActor (actorName: String, actorLevel: Int): Unit = {
    repeat(actorLevel){ print("  ")}
    print(actorName)
    var n = actorLevel*2 + actorName.length
    while (n < 60) { print(' '); n += 1 }
  }
  def showActorState (latency: Long, procMsgs: Long): Unit = {
    println(f"   ${latency/1000}%8d   $procMsgs%8d")
  }

  def handleShowRaceActor (originator: ActorRef, sentNanos: Long): Unit = {
    showActor(name,level)
    showActorState( System.nanoTime - sentNanos, nMsgs)
    originator ! ShowRaceActorResponse
  }

  def handlePauseRaceActor  (originator: ActorRef) = {
    info(s"got PauseRaceActor from: ${originator.path}")
    try {
      val success = onPauseRaceActor(originator)
      if (success){
        status = Paused
        info("paused")
        sender() ! RaceActorPaused
      } else {
        warning("pause rejected")
        sender() ! RaceActorPauseFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender() ! RaceActorPauseFailed(ex.getMessage)
    }
  }

  def handleRacePaused (originator: ActorRef): Unit = {
    info(s"got RacePaused from $originator")
    if (originator == master) {
      onRacePaused
    } else {
      warning(s"RacePaused ignored - sender is not master")
    }
  }

  def handleRacePauseFailed(originator: ActorRef, details: Any): Unit = {
    info(s"got RacePauseFailed($details) from $originator")
    if (originator == master) {
      onRacePauseFailed(details)
    } else {
      warning(s"RacePauseFailed ignored - sender is not master")
    }
  }

  def handleResumeRaceActor  (originator: ActorRef) = {
    info(s"got ResumeRaceActor from: ${originator.path}")
    try {
      val success = onResumeRaceActor(originator)
      if (success){
        status = Running
        info("resumed")
        sender() ! RaceActorResumed
      } else {
        warning("resume rejected")
        sender() ! RaceActorResumeFailed("rejected")
      }
    } catch {
      case ex: Throwable => sender() ! RaceActorResumeFailed(ex.getMessage)
    }
  }

  def handleRaceResumed (originator: ActorRef): Unit = {
    info(s"got RaceResumed from $originator")
    if (originator == master) {
      onRaceResumed
    } else {
      warning(s"RaceResumed ignored - sender is not master")
    }
  }

  def handleRaceResumeFailed(originator: ActorRef, details: Any): Unit = {
    info(s"got RaceResumeFailed($details) from $originator")
    if (originator == master) {
      onRaceResumeFailed(details)
    } else {
      warning(s"RaceResumeFailed ignored - sender is not master")
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
          sender() ! RaceActorTerminated
        } else {
          warning("terminate rejected")
          sender() ! RaceActorTerminateFailed("rejected")
        }
      } catch {
        case x: Throwable =>
          x.printStackTrace()
          sender() ! RaceActorTerminateFailed(x.toString)
      }
    } else {
      info("ignored remote TerminateRaceActor")
      sender() ! RaceActorTerminateIgnored
    }
  }

  def handleSyncWithRaceClock = {
    if (onSyncWithRaceClock) {
      info("clock synced")
    } else {
      warning("clock sync rejected")
    }
  }

  def handleSyncRequest (req: SyncRequest): Unit = {
    if (req.responderType.isEmpty || req.responderType.get.isAssignableFrom(getClass)) {
      if (onSyncRequest(req.originator)) req.originator ! SyncResponse(self, getClass, req)
    }
  }

  def handleSyncResponse (responder: ActorRef, responderType: Class[_], req: SyncRequest): Unit = {
    if (self == req.originator) onSyncResponse(responder, responderType, req.tag: Any)
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

  def requestPause: Unit = master ! RacePauseRequest
  def onRacePaused: Unit = {} // override if we are a valid requester
  def onRacePauseFailed(details: Any) = {}

  def requestResume: Unit = master ! RaceResumeRequest
  def onRaceResumed: Unit = {} // override if we are a valid requester
  def onRaceResumeFailed(details: Any): Unit = {}

  // if this returns true we respond to this request
  def onSyncRequest (originator: ActorRef): Boolean = true
  def onSyncResponse (responder: ActorRef, responderType: Class[_], tag: Any): Unit = {}

  def requestTermination: Unit = master ! RaceTerminateRequest // there is no feedback message for this

  def isPaused: Boolean = raceActorSystem.isPaused
  def isRunning: Boolean = raceActorSystem.isRunning
  def isTerminating: Boolean = raceActorSystem.isTerminating

  def loadClass[T] (clsName: String, clsType: Class[T]): Class[_ <:T] = {
    ClassLoaderUtils.loadClass(context.system, clsName, clsType)
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

  def scheduleRecurring (interval: FiniteDuration, msg: Any) : Cancellable = {
    scheduler.scheduleWithFixedDelay(0.seconds, interval, self, msg)
  }

  def scheduleOnceIn (delay: FiniteDuration, msg: Any): Cancellable = scheduler.scheduleOnce(delay,self,msg)

  def scheduleOnce (delay: FiniteDuration)(f: =>Unit): Cancellable = scheduler.scheduleOnce(delay)(f)

  def delay (d: FiniteDuration, action: ()=>Unit): Option[Cancellable] = Some(scheduler.scheduleOnce(d,self,DelayedAction(self,action)))


  //--- timeout values we might need during actor initialization in order to clean up
  protected def _getTimeout(key: String) = config.getFiniteDurationOrElse(key,raceActorSystem.defaultActorTimeout)
  def createTimeout = _getTimeout("create-timeout")
  def initTimeout = _getTimeout("init-timeout")
  def startTimeout = _getTimeout("start-timeout")

}












