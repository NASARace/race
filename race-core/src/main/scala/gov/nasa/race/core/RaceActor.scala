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
import gov.nasa.race.{common, _}
import gov.nasa.race.common.Status._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{InitializeRaceActor, StartRaceActor, _}
import gov.nasa.race.util.ClassLoaderUtils
import org.joda.time.DateTime

import scala.collection.mutable.{Set => MutableSet}
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
  var raceContext: RaceContext = null

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

  override def postStop = RaceActorSystem(context.system).stoppedRaceActor(self)

  // pre-init behavior which doesn't branch into concrete actor code before we
  // do the initialization. This guarantees that concrete actor code cannot access
  // un-initialized fields
  override def receive = {
    case InitializeRaceActor(raceContext,actorConf) => handleInitializeRaceActor(raceContext,actorConf)
    case TerminateRaceActor(originator) => handleTerminateRaceActor(originator)
    case msg: PingRaceActor => sender ! msg.copy(tReceivedNanos=System.nanoTime())

    case msg => info(f"ignored in pre-init mode: $msg%30.30s..")
  }

  def handleInitializeRaceActor (rctx: RaceContext, actorConf: Config) = {
    info("got InitializeRaceActor")
    try {
      raceContext = rctx
      context.become(receiveLive)
      onInitializeRaceActor(rctx, actorConf)
      status = Initialized
      sender ! RaceActorInitialized
      info("initialized")
    } catch {
      case ex: Throwable => sender ! RaceActorInitializeFailed(ex.getMessage)
    }
  }

  def receiveLive = { // chained message processing
    handleMessage orElse handleSystemMessage
  }

  /**
    * this is the main extension point - override for processing any messages other
    * than Race system messages
    */
  def handleMessage: Receive = {
    case null => // ignore
  }

  def handleSystemMessage: Receive = {
    case InitializeRaceActor(rc,actorConf) => handleLiveInitializeRaceActor(rc, actorConf)
    case StartRaceActor(originator) => handleStartRaceActor(originator)
    case TerminateRaceActor(originator) => handleTerminateRaceActor(originator)

    case ProcessRaceActor => sender ! RaceActorProcessed
    case msg@ PingRaceActor(tSent,_) =>
      sender ! msg.copy(tReceivedNanos=System.nanoTime())

    case rc: ChildNodeRollCall => answerChildNodes(rc)
    case rc: RollCall => rc.answer(self) // generic response

    case SetLogLevel(newLevel) => logLevel = newLevel

    case SetTimeout(msg,duration) =>
      context.system.scheduler.scheduleOnce(duration, self, msg)
  }

  def handleLiveInitializeRaceActor (rc: RaceContext, actorConf: Config) = {
    if (rc != raceContext) {
      info(s"got remote InitializeRaceActor from: ${rc.masterRef.path}")
      try {
        raceContext = rc
        onReInitializeRaceActor(rc, actorConf)
        sender ! RaceActorInitialized
        info("reinitialized")
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
      if (status == Running) {
        onReStartRaceActor(originator)
        info("restarted")
      } else {
        onStartRaceActor(originator)
        status = Running
        info("started")
      }
      sender ! RaceActorStarted
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
        onTerminateRaceActor(originator)
        status = common.Status.Terminated
        // note that we don't stop this actor - that is the responsibility of the master/ras
        sender ! RaceActorTerminated
        info("terminated")
      } catch {
        case ex: Throwable => sender ! RaceActorTerminateFailed(ex.getMessage)
      }
    } else {
      info("ignored remote TerminateRaceActor")
      sender ! RaceActorTerminateIgnored
    }
  }

  //--- the general system message callbacks

  // note that RaceActor itself does not depend on overrides properly calling super.onXX(), all the
  // critical system processing we do in the non-overridden handleXX() that call the overridable onXX()

  // we allow non-Unit returns because FSMRaceActors need them for state transitions. However, there is no
  // return value processing in plain RaceActors

  // Note alse that there are no separate ReXX messages, calling onReXX() is just done on the basis of the current
  // RaceActor state (we might support re-initialization in local RAS in the future)

  def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Any = {}
  def onReInitializeRaceActor(rc: RaceContext, actorConf: Config): Any = {}

  def onStartRaceActor(originator: ActorRef): Any = {}
  def onReStartRaceActor(originator: ActorRef): Any = {}

  def onPauseRaceActor(originator: ActorRef): Any = {}
  def onResumeRaceActor(originator: ActorRef): Any = {}

  def onTerminateRaceActor(originator: ActorRef): Any = {}

  //--- utilities for RaceActors with dependents (TODO - turn this into a interface)

  // this is the version that uses the same config for all dependents
  def initDependentRaceActors (actors: Seq[ActorRef], rc: RaceContext, actorConf: Config): Boolean = {
    askDependents(actors, InitializeRaceActor(rc,actorConf), RaceActorInitialized)
  }
  def startDependentRaceActors (actors: Seq[ActorRef]): Boolean = askDependents(actors,StartRaceActor(self),RaceActorStarted)
  def terminateDependentRaceActors (actors: Seq[ActorRef]): Boolean = askDependents(actors,TerminateRaceActor(self),RaceActorTerminated)

  def askDependents (actors: Seq[ActorRef], question: Any, answer: Any): Boolean = {
    var result = true
    actors.foreach { actorRef =>
      askForResult (actorRef ? question){
        case `answer` => // all fine
        case TimedOut =>
          warning(s"dependent actor timed out: ${actorRef.path.name}")
          result = false
      }
    }
    result
  }

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
 * FSMRaceActor is used to interface the standard Akka FSM actor type with RaceActor specifics.
 * Since FSM has its own receive() definition that we should not override, we have to make sure
 * the system messages are still properly called in case concrete FSMRaceActors don't process them
 * explicitly (we should not override receive() to implement a chain, like RaceActor does). We also
 * have to make sure that system message callbacks are properly invoked regardless of FSM state,
 * which is why we provide a respective whenUnhandled block
 */
trait FSMRaceActor[S,D] extends FSM[S,D] with  RaceActor {

  // make sure we use the FSM handler no matter in what trait order FSMRaceActor is specified
  override final def receive = super[FSM].receive
  override final def handleMessage = super[FSM].receive

  // this takes the role of handleSystemMessage since we can't bypass FSM.receive
  whenUnhandled {
    case Event(InitializeRaceActor(rc: RaceContext,conf: Config), _) =>
      val nextState = onInitializeRaceActor(rc, conf) match {
        case newState: State => newState
        case _ => stay
      }
      sender ! RaceActorInitialized
      nextState

    case Event(StartRaceActor(originator), _) =>
      onStartRaceActor(originator) match {
        case newState: State => newState
        case _ => stay
      }

    case Event(TerminateRaceActor(originator), _) =>
      val nextState = onTerminateRaceActor(originator) match {
        case newState: State => newState
        case _ => stay
      }
      sender ! RaceActorTerminated
      nextState

    case Event(rc: ChildNodeRollCall, _) =>
      answerChildNodes(rc)
      stay
  }

  onTransition {
    case s1 -> s2 =>
      log.info(s"${name} switching from state '$s1' to '$s2'")
  }

  // make sure we don't handle system messages
  def nothingWhen(s: S) = {
    when(s) {
      case null => stay
    }
  }
}

/**
 * a RaceActor that can publish to the Bus
 */
trait PublishingRaceActor extends RaceActor {
  var writeTo = MutableSet.empty[String]

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Any = {
    super.onInitializeRaceActor(raceContext, actorConf)
    writeTo ++= actorConf.getOptionalStringList("write-to")
  }

  // we just add the new channels
  override def onReInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Any = {
    super.onReInitializeRaceActor(raceContext, actorConf)
    writeTo ++= actorConf.getOptionalStringList("write-to")
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

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Any = {
    super.onInitializeRaceActor(raceContext,actorConf)
    readFrom ++= actorConf.getOptionalStringList("read-from")
    readFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
  }

  // we add new channels and (re-)subscribe to everything since old channels might now be global
  override def onReInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Any = {
    super.onReInitializeRaceActor(raceContext,actorConf)
    val newChannels = actorConf.getOptionalStringList("read-from")
    // re-subscription is benign in case we already were subscribed (bus keeps subscribers as sets)
    readFrom ++= newChannels
    //newChannels.foreach { channel => busFor(channel).subscribe(self,channel) }
    readFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
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

  override def onTerminateRaceActor(originator: ActorRef): Any = {
    super.onTerminateRaceActor(originator)
    unsubscribeAll
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
  val simClock = RaceActorSystem(context.system).simClock

  var lastSimMillis: Long = simClock.baseMillis
  var startSimTimeMillis: Long = 0
  var startWallTimeMillis: Long = 0

  override def onStartRaceActor(originator: ActorRef): Any = {
    startSimTimeMillis = updatedSimTimeMillis
    startWallTimeMillis = System.currentTimeMillis()
    super.onStartRaceActor(originator)
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

  def elapsedSimTimeSince (dt: DateTime) = Duration(max(0,lastSimMillis - dt.getMillis), MILLISECONDS)
  def elapsedSimTimeMillisSince (dt: DateTime) = lastSimMillis - dt.getMillis

  def elapsedSimTimeSinceStart = Duration(lastSimMillis - startSimTimeMillis, MILLISECONDS)
  def elapsedSimTimeMillisSinceStart = lastSimMillis - startSimTimeMillis

  def toWallTimeMillis (d: Duration) = (d.toMillis * simClock.timeScale).toLong
  def toWallTimeMillis (ms: Long) = (ms * simClock.timeScale).toLong
}

/**
  * a RaceActor that receives periodic RaceCheck messages, which should be processed
  * by its handleMessage().
  *
  * NOTE - it is up to the concrete actor to decide when to call startMonitoring()
  * (from ctor, initializeRaceActor or startRaceActor)
  * This honors remote config check-interval spec, which would re-start a already
  * running scheduler
  */
trait MonitoredRaceActor extends RaceActor {
  final val CheckIntervalKey = "check-interval"
  final val CheckDelayKey = "check-delay"

  // override if there are different default values
  def defaultCheckInterval = 5.seconds
  def defaultCheckDelay = 0.seconds

  var checkInterval = config.getFiniteDurationOrElse(CheckIntervalKey, defaultCheckInterval)
  var checkDelay = config.getFiniteDurationOrElse(CheckDelayKey, defaultCheckDelay)
  var schedule: Option[Cancellable] = None

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    super.onInitializeRaceActor(rc, actorConf)

    if (!isLocalContext(rc)) {
      // check if we have a different remote check interval
      if (actorConf.hasPath(CheckIntervalKey)) {
        checkInterval = actorConf.getFiniteDuration(CheckIntervalKey)
        if (schedule.isDefined){
          stopMonitoring
          startMonitoring
        }
      }
    }
  }

  override def onTerminateRaceActor(originator: ActorRef): Any = {
    super.onTerminateRaceActor(originator)
    stopMonitoring
  }

  def startMonitoring = {
    if (schedule.isEmpty) {
      schedule = Some(scheduler.schedule(checkDelay, checkInterval, self, RaceCheck))
    }
  }

  def stopMonitoring = {
    ifSome(schedule) { sched =>
      sched.cancel()
      schedule = None
    }
  }

  override def commitSuicide (errMsg: String) = {
    stopMonitoring
    super.commitSuicide(errMsg)
  }
}