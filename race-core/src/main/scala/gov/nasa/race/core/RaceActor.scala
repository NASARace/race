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
import akka.pattern.ask
import com.typesafe.config.Config
import gov.nasa.race.common.Clock
import gov.nasa.race.common.Status._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{InitializeRaceActor, StartRaceActor, _}
import gov.nasa.race.util.ClassLoaderUtils
import gov.nasa.race.{common, _}
import org.joda.time.DateTime

import scala.collection.mutable.{ArrayBuffer, Set => MutableSet}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.math._
import scala.reflect.ClassTag

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

  var status = Initializing
  val localRaceContext: RaceContext = RaceActorSystem(system).localRaceContext
  var raceContext: RaceContext = null  // set during InitializeRaceActor
  
  if (supervisor == localMaster) { // means we are a toplevel RaceActor
    info(s"registering toplevel actor $self")
    raceActorSystem.registerActor(self, config, getCapabilities)
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
    caps + IsAutomatic + SupportsSimTime + SupportsSimTimeReset
  }

  override def postStop = RaceActorSystem(context.system).stoppedRaceActor(self)

  // pre-init behavior which doesn't branch into concrete actor code before we
  // do the initialization. This guarantees that concrete actor code cannot access
  // un-initialized fields
  override def receive = {
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
    case TerminateRaceActor(originator) => handleTerminateRaceActor(originator)

    case ProcessRaceActor => sender ! RaceActorProcessed
    case msg:PingRaceActor => sender ! msg.copy(tReceivedNanos=System.nanoTime())

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
        case ex: Throwable => sender ! RaceActorTerminateFailed(ex.getMessage)
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

  // Note alse that there are no separate ReXX messages, calling onReXX() is just done on the basis of the current
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

  // NOTE: apparently Scala 2.12.1 does widen the type if the generic type parameter is
  // inferred, which can lead to runtime exceptions. Specify the generic type explicitly!

  def getConfigurable[T: ClassTag](conf: Config): T = {
    val clsName = conf.getString("class")
    info(s"instantiating $clsName")
    newInstance[T](clsName, Array(classOf[Config]), Array(conf)).get
  }

  def getConfigurable[T: ClassTag](key: String): T = getConfigurable(config.getConfig(key))

  def getConfigurableOrElse[T: ClassTag](key: String, f: => T): T = {
    config.getOptionalConfig(key) match {
      case Some(conf) =>
        val clsName = conf.getString("class")
        newInstance[T](clsName, Array(classOf[Config]), Array(conf)).get
      case None => f
    }
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

  def delay (d: FiniteDuration, action: ()=>Unit): Option[Cancellable] = Some(scheduler.scheduleOnce(d,self,DelayedAction(self,action)))

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

/**
  * a RaceActor that itself creates and manages a set of RaceActor children
  *
  * the main purpose of this trait is to keep a list of child actor infos (actorRef and config), and
  * to manage the state callbacks for them (init,start,termination)
  */
trait ParentRaceActor extends RaceActor with ParentContext {
  val children: ArrayBuffer[RaceActorRec] = new ArrayBuffer[RaceActorRec]

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    // TODO - shouldn't we delegate to super before forwarding to children?
    if (initializeChildActors(rc,actorConf)) {
      super.onInitializeRaceActor(rc, actorConf)
    } else false
  }
  override def onStartRaceActor(originator: ActorRef) = {
    if (startChildActors) {
      super.onStartRaceActor(originator)
    } else false
  }
  override def onTerminateRaceActor(originator: ActorRef) = {
    if (terminateChildActors) {
      super.onTerminateRaceActor(originator)
    } else false
  }
  // TODO - add onRestart and onPause forwarding

  def initializeChildActors (rc: RaceContext, actorConf: Config): Boolean = {
    // we can't use askChildren because we need a different message object for each of them
    !children.exists { e=> !askChild(e,InitializeRaceActor(rc,e.config),RaceActorInitialized) }
  }

  def startChildActors: Boolean = askChildren(StartRaceActor(self),RaceActorStarted)

  def terminateChildActors: Boolean = askChildrenReverse(TerminateRaceActor(self),RaceActorTerminated)

  def askChild (actorRef: ActorRef, question: Any, answer: Any): Boolean = {
    askForResult (actorRef ? question){
      case `answer` => true
      case TimedOut => warning(s"dependent actor timed out: ${actorRef.path.name}"); false
    }
  }
  def askChildren (question: Any, answer: Any): Boolean = !children.exists(!askChild(_,question,answer))

  def askChildrenReverse (question: Any, answer: Any): Boolean = {
    for ( cr <- children.reverseIterator ){
      if (!askChild(cr,question,answer)) return false
    }
    true
  }

  def actorOf(props: Props, name: String): ActorRef = context.actorOf(props,name)

  def addChild (raRec: RaceActorRec): Unit = children += raRec

  @inline def childActorRef (i: Int) = children(i).actorRef
}

/**
  * pure interface that hides parent actor details from child actor creation context
  */
trait ParentContext {
  def name: String
  def system: ActorSystem
  def self: ActorRef

  //--- actor instantiation
  def instantiateActor (actorName: String, actorConfig: Config): ActorRef
  def actorOf(props: Props, name: String): ActorRef

  // general instantiation
  def newInstance[T: ClassTag](clsName: String,
                               argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T]
  // and probably some more..

  def addChild (raRec: RaceActorRec): Unit
}

/**
 * a RaceActor that can publish to the Bus
 */
trait PublishingRaceActor extends RaceActor {
  var writeTo = MutableSet.empty[String]

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    writeTo ++= actorConf.getOptionalStringList("write-to")
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  // we just add the new channels
  override def onReInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    writeTo ++= actorConf.getOptionalStringList("write-to")
    super.onReInitializeRaceActor(raceContext, actorConf)
  }

  // publish on all configured channels
  def publish (msg: Any): Unit = {
    writeTo.foreach { publish(_,msg) }
  }

  def publish (channel: String, msg: Any): Unit = {
    busFor(channel).publish( BusEvent(channel,msg,self))
  }

  def publishBusEvent (e: BusEvent): Unit = {
    writeTo.foreach { publishBusEvent(_,e) }
  }

  // can be used for re-publishing BusEvents on a different channel
  def publishBusEvent (otherChannel: String, e: BusEvent): Unit = {
    val be = if (e.channel == otherChannel) e else e.copy(channel=otherChannel)
    busFor(otherChannel).publish(be)
  }

  def hasPublishingChannels = writeTo.nonEmpty
}

/**
 * a RaceActor that can subscribe to the Bus
 */
trait SubscribingRaceActor extends RaceActor {
  var readFrom = MutableSet.empty[String]

  //--- pre-init channel setting
  def addSubscription (channel: String*) = readFrom ++= channel
  def addSubscriptions (channels: Seq[String]) = readFrom ++= channels

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    readFrom ++= actorConf.getOptionalStringList("read-from")
    readFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onInitializeRaceActor(raceContext,actorConf)
  }

  // we add new channels and (re-)subscribe to everything since old channels might now be global
  override def onReInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    val newChannels = actorConf.getOptionalStringList("read-from")
    // re-subscription is benign in case we already were subscribed (bus keeps subscribers as sets)
    readFrom ++= newChannels
    //newChannels.foreach { channel => busFor(channel).subscribe(self,channel) }
    readFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onReInitializeRaceActor(raceContext,actorConf)
  }

  //--- dynamic subscriptions
  def subscribe(channel: String) = {
    readFrom += channel
    busFor(channel).subscribe(self,channel)
  }
  def unsubscribe(channel: String) = {
    readFrom -= channel
    busFor(channel).unsubscribe(self,channel)
  }

  def unsubscribeAll: Unit = {
    readFrom.foreach { channel => busFor(channel).unsubscribe(self,channel) }
    readFrom.clear
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    unsubscribeAll
    super.onTerminateRaceActor(originator)
  }

  def readFromAsString = readFrom.mkString(",")
}



/**
 * a RaceActor that uses simulation time and keeps track of the last time
  * the simulation clock was accessed/updated.
  * It acts both as a simClock access API and a time value cache
  *
  * Note that we share a single simTime Clock for the whole actor system
  * we are running in
 */
trait ContinuousTimeRaceActor extends RaceActor {
  private val ras = RaceActorSystem(context.system)
  val simClock: Clock = ras.simClock
  final val canResetClock = config.getBooleanOrElse("can-reset-clock", false)

  var lastSimMillis: Long = simClock.baseMillis
  var startSimTimeMillis: Long = 0
  var startWallTimeMillis: Long = 0

  override def onStartRaceActor(originator: ActorRef) = {
    startSimTimeMillis = updatedSimTimeMillis
    startWallTimeMillis = System.currentTimeMillis()
    super.onStartRaceActor(originator)
  }

  override def onSyncWithRaceClock = {
    startSimTimeMillis = simClock.millis
    lastSimMillis = startSimTimeMillis
    super.onSyncWithRaceClock
  }

  @inline def updateSimTime = lastSimMillis = simClock.millis
  @inline def simTime = new DateTime(lastSimMillis)
  @inline def simTimeMillis = lastSimMillis
  @inline def updatedSimTime = {
    lastSimMillis = simClock.millis
    new DateTime(lastSimMillis)
  }
  @inline def updatedSimTimeMillis = {
    lastSimMillis = simClock.millis
    lastSimMillis
  }

  def updateElapsedSimTime: FiniteDuration = {
    val now = simClock.millis
    val dt = now - lastSimMillis
    lastSimMillis = now
    Duration(dt, MILLISECONDS)
  }

  // for a context in which we can't create objects
  def updateElapsedSimTimeMillis: Long = {
    val now = simClock.millis
    val dt = now - lastSimMillis
    lastSimMillis = now
    dt
  }
  def updateElapsedSimTimeMillisSince (dt: DateTime): Long = {
    lastSimMillis = simClock.millis
    lastSimMillis - dt.getMillis
  }
  def updatedElapsedSimTimeMillisSinceStart: Long = {
    lastSimMillis = simClock.millis
    lastSimMillis - startSimTimeMillis
  }

  @inline final def currentSimTimeMillis = simClock.millis
  @inline final def currentWallTimeMillis = System.currentTimeMillis()

  @inline def currentSimTimeMillisSinceStart = currentSimTimeMillis - startSimTimeMillis
  @inline def currentWallTimeMillisSinceStart = currentWallTimeMillis - startWallTimeMillis

  // those are based on the last update
  def elapsedSimTimeSince (dt: DateTime) = Duration(max(0,lastSimMillis - dt.getMillis), MILLISECONDS)
  def elapsedSimTimeMillisSince (dt: DateTime) = lastSimMillis - dt.getMillis

  def elapsedSimTimeSinceStart = Duration(lastSimMillis - startSimTimeMillis, MILLISECONDS)
  @inline def elapsedSimTimeMillisSinceStart = lastSimMillis - startSimTimeMillis

  @inline def toWallTimeMillis (d: Duration) = (d.toMillis * simClock.timeScale).toLong
  @inline def toWallTimeMillis (ms: Long) = (ms * simClock.timeScale).toLong

  @inline def exceedsEndTime (d: DateTime) = simClock.exceedsEndTime(d)

  final def resetSimClockRequest (d: DateTime, timeScale: Double = 1.0): Boolean = {
    if (canResetClock) {
      if (raceActorSystem.resetSimClockRequest(self,d,timeScale)){
        onSyncWithRaceClock
        true
      } else {
        warning("RAS rejected sim clock reset")
        false
      }
    } else {
      warning("ignored clock reset request (set 'can-reset-clock')")
      false
    }
  }
}

/**
  * a RaceActor that receives periodic RaceCheck messages, which should be processed
  * by its handleMessage().
  *
  * NOTE - it is up to the concrete actor to decide when to call startScheduler()
  * (from ctor, initializeRaceActor or startRaceActor)
  * This honors remote config tick-interval specs, which would re-start an already
  * running scheduler
  */
trait PeriodicRaceActor extends RaceActor {
  final val TickIntervalKey = "tick-interval"
  final val TickDelayKey = "tick-delay"

  // override if there are different default values
  def defaultTickInterval = 5.seconds
  def defaultTickDelay = 0.seconds
  def tickMessage = RaceTick

  var tickInterval = config.getFiniteDurationOrElse(TickIntervalKey, defaultTickInterval)
  var tickDelay = config.getFiniteDurationOrElse(TickDelayKey, defaultTickDelay)
  var schedule: Option[Cancellable] = None

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    if (!isLocalContext(rc)) {
      // check if we have a different remote tick interval
      if (actorConf.hasPath(TickIntervalKey)) {
        tickInterval = actorConf.getFiniteDuration(TickIntervalKey)
        if (schedule.isDefined){
          stopScheduler
          startScheduler
        }
      }
    }

    super.onInitializeRaceActor(rc, actorConf)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    stopScheduler
    super.onTerminateRaceActor(originator)
  }

  def startScheduler = {
    if (schedule.isEmpty) {
      schedule = Some(scheduler.schedule(tickDelay, tickInterval, self, tickMessage))
    }
  }

  def stopScheduler = {
    ifSome(schedule) { sched =>
      sched.cancel()
      schedule = None
    }
  }

  override def commitSuicide (errMsg: String) = {
    stopScheduler
    super.commitSuicide(errMsg)
  }
}

/**
  * a trait that processes events that have a stored timestamp, conditionally
  * adjusting the simClock if the timestamp of the first checked events differs
  * for more than a configured duration.
  *
  * This is useful for replay if we don't a priori know the sim start time
  */
trait ClockAdjuster extends ContinuousTimeRaceActor {

  val allowFutureReset = config.getBooleanOrElse("allow-future-reset", false)
  val maxSimClockDiff: Long = config.getOptionalFiniteDuration("max-clock-diff") match {  // optional, in sim time
    case Some(dur) => dur.toMillis
    case None => -1
  }

  private var isFirstClockCheck = true

  // overridable in subtypes
  protected def checkClockReset(d: DateTime): Unit = {
    onSyncWithRaceClock // make sure there are no pending resets we haven't processed yet
    val elapsedMillis = elapsedSimTimeMillisSince(d)
    val dt = if (allowFutureReset) Math.abs(elapsedMillis) else elapsedMillis
    if (dt > maxSimClockDiff) {
      resetSimClockRequest(d)
    }
  }

  /**
    * this is the checker function that has to be called by the type that mixes in ClockAdjuster,
    * on each event that might potentially be the first one to trigger adjustment of the global clock
    */
  @inline final def checkInitialClockReset(d: DateTime) = {
    if (canResetClock && isFirstClockCheck) {
      checkClockReset(d)
      isFirstClockCheck = false
    }
  }
}