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

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.pattern.AskSupport
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.util.StringUtils._
import gov.nasa.race.util.ThreadUtils
import gov.nasa.race.util.ThreadUtils._

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps



// these are only exchanged between masters during remote actor creation
// note - we can't use protected or objects because that would not match between different processes
case class RemoteConnectionRequest (requestingMaster: ActorRef)
case object RemoteConnectionAccept
case object RemoteConnectionReject

case object HeartBeat

/**
  * the master for a RaceActorSystem
  *
  * main purpose of the Master is to create all the toplevel RaceActors that are
  * defined in the config. This needs to happen here so that we have a clear supervisor
  * hierarchy (supervisors are responsible for failure management, whereas monitors can
  * only react to the death of monitored actors).
  *
  * Note that we can only monitor remote actors that are already running. Lifetime control
  * of such actors has to happen through the respective master of the RaceActorSystem that
  * created them. However, if we create a remote actor we do become the supervisor
  *
  * Actor initialization happens synchronously in two phases, both in the order in which
  * actors are defined in the config. While this runs against normal actor system convention
  * that tries to minimize hard synchronization, deterministic startup and shutdown behavior
  * is more important than startup time, especially given that we might have running satellites
  * representing complex external systems.
  *
  * Phase 1: create local (non-remote) actor, try to lookup running remote actor. If there is
  * no response create the remote actor. During the lookup, obtain the master actor of the remote
  * node
  *
  * Phase 2: dynamic initialization of all participating RaceActors with local actor configs
  *
  * Shutdown/termination is also synchronous, in reverse order of actor specification to guarantee
  * symmetric behavior
  *
  * TODO - streamline local/remote instantiation and registration
  */
class MasterActor (ras: RaceActorSystem) extends Actor with ParentActor {

  info(s"master created: ${self.path}")

  var heartBeatScheduler: Option[Cancellable] = None
  var heartBeatCount: Int = 0

  var waiterForTerminated: Option[ActorRef] = None

  //--- convenience funcs
  def name = self.path.name
  def simClock = ras.simClock
  def usedRemoteMasters = ras.usedRemoteMasters
  def localContext = ras.localRaceContext

  //context.system.eventStream.subscribe(self, classOf[DeadLetter])

  def executeProtected (f: => Unit): Unit = {
    try f catch {
      case x: Throwable => error(s"master caught exception in receive(): $x")
    }
  }

  def isLegitimateRequest(msg: Any): Boolean = ras.isVerifiedSenderOf(msg) || ras.isKnownRemoteMaster(sender)

  /**
    *   master message processing
    *   verify that critical messages come from a legit source (our RAS or remote master), and
    *   make sure we catch any exceptions - the master is the one actor that is not allowed to crash, it
    *   has to stay responsive
    */
  override def receive: Receive = {
    case RemoteConnectionRequest(remoteMaster) => onRemoteConnectionRequest(remoteMaster)

    case HeartBeat => onHeartBeat
    case msg: PingRaceActorResponse => onPingRaceActorResponse(sender, msg)

    case RaceCreate => onRaceCreate
    case RaceInitialize => onRaceInitialize
    case RaceStart => onRaceStart
    case RemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) => onRemoteRaceStart(remoteMaster,simTime,timeScale)

    case msg:SetLogLevel => sendToChildren(msg) // just forward

    case RacePause => onRacePause
    case RaceResume => onRaceResume

    case RaceTerminateRequest => // from some actor, let the RAS decide
      info(s"master $name got RaceTerminateRequest from $sender")
      ras.requestTermination(sender)

    case RaceTerminate => onRaceTerminate
    case RemoteRaceTerminate (remoteMaster: ActorRef) => onRemoteRaceTerminate(remoteMaster)

    case rrc@RaceResetClock(originator,d,tScale) =>
      info(s"master $name got $rrc")
      onRaceResetClock(originator,d,tScale) // RAS changed simClock, inform actors

    //--- inter RAS time management
      // TODO - this needs to be unified with SyncWithRaceClock
    case SyncSimClock(dateTime: DateTime, timeScale: Double) =>
      simClock.reset(dateTime,timeScale)
      sender ! RaceAck
    case StopSimClock =>
      simClock.stop
      sender ! RaceAck
    case ResumeSimClock =>
      simClock.resume
      sender ! RaceAck

    case Terminated(actorRef) => // Akka death watch event
      removeChildActorRef(actorRef)

    case RaceActorStopped => // from postStop hook of RaceActors
     stoppedChildActorRef(sender)

    case RaceShow => showActors(sender) // debug dump

    case deadLetter: DeadLetter => // TODO - do we need to react to DeadLetters?
  }

  //--- failure strategy

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case aix: ActorInitializationException =>
      ras.reportException(aix)
      error(aix.getMessage)
      Stop
    case x: Throwable =>
      ras.reportException(x)
      error(x.getMessage)
      Stop

    //case _: ArithmeticException      => Resume
    //case _: NullPointerException     => Restart
    //case _: IllegalArgumentException => Stop
    //case _: Exception                => Escalate
  }

  //--- remote connection management

  def onRemoteConnectionRequest(remoteMaster: ActorRef) = executeProtected {
    info(s"received remote connection request from: ${remoteMaster.path}")
    if (ras.acceptsRemoteConnectionRequest(remoteMaster)) {
      info(s"accepted remote connection request from: ${remoteMaster.path}")
      sender ! RemoteConnectionAccept
    } else {
      warning(s"rejected remote connection request from: ${remoteMaster.path}")
      sender ! RemoteConnectionReject
    }
  }

  //--- aux functions

  def isOptionalActor (actorConfig: Config) = actorConfig.getBooleanOrElse("optional", false)

  def getTimeout(conf: Config, key: String): Timeout = {
    if (conf.hasPath(key)) Timeout(conf.getFiniteDuration(key)) else timeout
  }

  //--- creation of RaceActors

  // this is in an ask pattern sent from the RAS
  def onRaceCreate = executeProtected {
    val originator = sender

    if (ras.isVerifiedSenderOf(RaceCreate)) {
      try {
        ras.getActorConfigs.foreach { actorConf =>
          val actorName = actorConf.getString("name")
          info(s"creating $actorName")

          getActor(actorConf) match {
            case Some(actorRef) =>
              addChildActorRef(actorRef,actorConf)
              context.watch(actorRef)
            case None =>
              error(s"creation of $actorName failed without exception")
              originator ! RaceCreateFailed("actor construction failed without exception: $actorName")
          }
        }
        // all accounted for
        originator ! RaceCreated // only gets sent out if there was no exception
      } catch {
        case x: Throwable => originator ! RaceCreateFailed(x)
      }
    } else {
      warning(s"RaceCreate request from $originator ignored")
    }
  }

  def getActor (actorConfig: Config): Option[ActorRef] = {
    val actorName = actorConfig.getString("name")

    actorConfig.getOptionalString("remote") match {

      case None => // local actor, the dominant case
        instantiateLocalActor(actorConfig)

      case Some(remoteUri) => // remote actor
        val isOptional = actorConfig.getBooleanOrElse("optional", false)
        val remoteUniverseName = userInUrl(remoteUri).get
        if (usedRemoteMasters.get(remoteUri).isEmpty) {
          if (isConflictingHost(remoteUri)){
            if (isOptional) {
              warning(s"ignoring $actorName from conflicting host $remoteUri")
              return None
            }
            else throw new RaceException("conflicting host of $remoteUri")
          } else {
            lookupRemoteMaster(remoteUniverseName, remoteUri, isOptional) match {
              case Some(remoteMasterRef) =>
                ras.usedRemoteMasters = usedRemoteMasters + (remoteUri -> remoteMasterRef)
              case None => return None // no (optional) satellite, nothing to look up or start
            }
          }
        }
        getRemoteActor(actorName,remoteUniverseName,remoteUri,actorConfig)
    }
  }

  //--- local actor creation

  /**
    * this is the workhorse for actor construction
    *
    * Note that it does NOT follow normal Akka convention because it has to construct
    * actors synchronously and tell us right away if there was an exception during construction.
    * We still need to be able to specify a supervisorStrategy though, i.e. we need to
    * instantiate through Props and from another thread.
    *
    * While it would be more idiomatic to use a Future here this would require yet another additional
    * thread / threadpool and all we need is to find out if the actor ctor completed in time and
    * with success/failure without resorting to busy waiting
    *
    * NOTE - since we use local synchronization this does not work for remote actor creation
    *
    * @param actorConfig the Config object that specifies the actor
    * @return Some(ActorRef) in case instantiation has succeeded, None otherwise
    */
  protected def instantiateLocalActor(actorConfig: Config): Option[ActorRef] = {
    try {
      val actorName = actorConfig.getString("name")
      val clsName = actorConfig.getClassName("class")
      val actorCls = ras.classLoader.loadClass(clsName)
      val createActor = getActorCtor(actorCls, actorConfig)

      info(s"creating $actorName ..")
      val sync = new LinkedBlockingQueue[Either[Throwable,ActorRef]](1)
      val props = getActorProps(createActor,sync)

      val aref = context.actorOf(props,actorName) // note this executes the ctor in another thread

      // here we have to run counter to normal Akka best practices and block until we
      // either know the actor construction has succeeded, failed with an exception, or
      // failed with a timeout. In case of exceptions we want to return right away
      // All failure handling has to consider if the actor is optional or not
      val createTimeout = actorConfig.getFiniteDurationOrElse("create-timeout",ras.defaultActorTimeout)

      def _timedOut: Option[ActorRef] = {
        if (isOptionalActor(actorConfig)) {
          warning(s"optional actor construction timed out $actorName")
          None
        } else {
          error(s"non-optional actor construction timed out $actorName, escalating")
          throw new RaceInitializeException(s"failed to create actor $actorName")
        }
      }

      waitInterruptibleUpTo(createTimeout, _timedOut) { dur =>
        sync.poll(dur.toMillis, TimeUnit.MILLISECONDS) match {
          case null => _timedOut

          case Left(t) => // ctor bombed
            if (isOptionalActor(actorConfig)) {
              warning(s"constructor of optional actor $actorName caused exception $t")
              None
            } else {
              error(s"constructor of non-optional actor $actorName caused exception $t, escalating")
              throw new RaceInitializeException(s"failed to create actor $actorName")
            }

          case Right(ar) => // success, we could check actorRef equality here
            info(s"actor $actorName created")
            Some(aref)
        }
      }

    } catch { // those are exceptions that happened in this thread
      case cnfx: ClassNotFoundException =>
        if (isOptionalActor(actorConfig)) {
          warning(s"optional actor class not found: ${cnfx.getMessage}")
          None
        } else {
          error(s"unknown actor class: ${cnfx.getMessage}, escalating")
          throw new RaceInitializeException(s"no actor class: ${cnfx.getMessage}")
        }
      case cx: ConfigException =>
        error(s"invalid actor config: ${cx.getMessage}, escalating")
        throw new RaceInitializeException(s"missing actor config: ${cx.getMessage}")
    }
  }

  /**
    * find a suitable actor constructor, which is either taking a Config argument or
    * no arguments at all. Raise a RaceInitializeException if none is found
    */
  protected def getActorCtor (actorCls: Class[_], actorConfig: Config): () => Actor = {
    try {
      val confCtor = actorCls.getConstructor(classOf[Config])
      () => { confCtor.newInstance(actorConfig).asInstanceOf[Actor] }
    } catch {
      case _: NoSuchMethodException =>
        try {
          val defCtor = actorCls.getConstructor()
          () => { defCtor.newInstance().asInstanceOf[Actor] }
        } catch {
          case _: NoSuchMethodException =>
            throw new RaceInitializeException(s"no suitable constructor in ${actorCls.getName}")
        }
    }
  }

  /**
    * create Props to construct an actor. Note that we need to use the Props constructor that
    * takes a creator function argument so that we can sync on the actor construction and
    * know if there was an exception in the respective ctor
    */
  protected def getActorProps (ctor: ()=>Actor, sync: LinkedBlockingQueue[Either[Throwable,ActorRef]]) = {
    Props( creator = {
      try {
        val a = ctor()
        sync.put(Right(a.self))
        a
      } catch {
        case t: Throwable =>
          ras.reportException(t)
          sync.put(Left(t))
          throw t
      }
    })
  }

  //--- remote actor creation/lookup

  def getRemoteActor(actorName: String, remoteUniverseName: String, remoteUri: String, actorConfig: Config): Option[ActorRef] = {
    val path = s"$remoteUri/user/$remoteUniverseName/$actorName"
    info(s"looking up remote actor $path")
    val sel = context.actorSelection(path)
    askForResult(sel ? Identify(path)) {
      case ActorIdentity(path: String, r@Some(actorRef:ActorRef)) =>
        info(s"response from remote $path")
        r
      case ActorIdentity(path: String, None) => None
        if (actorConfig.hasPath("class")) {
          instantiateRemoteActor(actorConfig)

        } else { // if there is no class, we can't instantiate
          if (actorConfig.getBooleanOrElse("optional", false)) {
            warning(s"ignoring optional remote $path")
            None
          } else {
            error(s"cannot instantiate remote because of missing class $path")
            throw new RaceException("missing remote actor ")
          }
        }
    }
  }

  def instantiateRemoteActor (actorConfig: Config): Option[ActorRef] = {
    val actorName = actorConfig.getString("name")
    try {
      val clsName = actorConfig.getClassName("class")
      val actorCls = ras.classLoader.loadClass(clsName)

      info(s"creating remote $actorName")
      val aRef = context.actorOf(Props(actorCls, actorConfig), actorName)
      Some(aRef)

    } catch {
      case t: Throwable =>
        if (isOptionalActor(actorConfig)) {
          warning(s"optional remote $actorName did not instantiate: $t")
          None
        } else {
          throw new RaceInitializeException(s"non-optional remote $actorName failed with: $t")
        }
    }
  }

  def isConflictingHost (remoteUri: String): Boolean = {
    val loc = stringTail(remoteUri,'@')
    usedRemoteMasters.exists(e => loc == stringTail(e._1,'@'))
  }

  def lookupRemoteMaster (remoteUniverseName: String, remoteUri: String, isOptional: Boolean): Option[ActorRef] = {
    val path = s"$remoteUri/user/$remoteUniverseName"
    info(s"looking up remote actor system $path")
    val sel = context.actorSelection(path)
    // two step process: (1) obtain the reference for a remote master, (2) p2p check if it is accepting the connection
    askForResult (sel ? Identify(path)) {
      case ActorIdentity(path: String, Some(actorRef:ActorRef)) =>
        info(s"found master for remote actor system: ${actorRef.path}")
        if (isRemoteConnectionAcceptedBy(actorRef)) {
          info(s"remote master accepted connection request: ${actorRef.path}")
          Some(actorRef)
        } else {
          warning(s"remote master did not accept connection request: ${actorRef.path}")
          None
        }
      case ActorIdentity(path: String, None) =>
        if (isOptional) {
          warning(s"no optional remote actor system: $remoteUri")
          None
        } else {
          error(s"no remote actor system: $remoteUri")
          throw new RaceException("satellite not found")
        }
      case TimedOut => // timeout is always an error since it indicates a network or satellite problem
        error(s"timeout for remote actor system: $remoteUri")
        throw new RaceException("satellite response timeout")
    }
  }

  def isRemoteConnectionAcceptedBy(remoteMaster: ActorRef): Boolean = {
    askForResult (remoteMaster ? RemoteConnectionRequest(self)) {
      case RemoteConnectionAccept => true
      case RemoteConnectionReject => false
      case TimedOut => false
    }
  }

  //--- actor initialization

  def onRaceInitialize = executeProtected {
    if (ras.isVerifiedSenderOf(RaceInitialize)) {
      val requester = sender // store before we send out / receive new messages
      try {
        val commonCaps = initializeRaceActors
        sender ! RaceInitialized(commonCaps)
      } catch {
        case x: Throwable =>
          //x.printStackTrace // TODO - exceptions should be loggable to a file
          requester ! RaceInitializeFailed(x)
        // shutdown is initiated by the sender
      }
    } else warning(s"RaceInitialize request from $sender ignored")
  }

  // cache to be only used during actor initialization
  protected val remoteContexts = mutable.Map.empty[UrlString,RaceContext]

  def initializeRaceActors: RaceActorCapabilities = {
    var commonCaps = RaceActorCapabilities.AllCapabilities

    actors.foreach { actorData =>
      val actorRef = actorData.actorRef
      val actorConfig = actorData.config
      val actorName = actorRef.path.name
      val isOptional = isOptionalActor(actorConfig)
      val initTimeout = Timeout(actorConfig.getFiniteDurationOrElse("init-timeout",ras.defaultActorTimeout))

      val raceContext = actorConfig.getOptionalString("remote") match {
        case Some(remoteUri) => remoteContexts.getOrElse(remoteUri, createRemoteRaceContext(remoteUri))
        case None => localContext
      }

      info(s"sending InitializeRaceActor to $actorName")
      askForResult(actorRef ? InitializeRaceActor(raceContext, actorConfig)) {
        case RaceActorInitialized(actorCaps) =>
          info(s"received RaceInitialized from $actorName")
          commonCaps = commonCaps.intersection(actorCaps)

          //--- these are all causing exceptions if this is not an optional actor
        case RaceActorInitializeFailed(reason) => initFailed(s"initialization of $actorName failed: $reason", isOptional)
        case TimedOut => initFailed(s"initialization timeout for $actorName", isOptional)
        case other => initFailed(s"invalid initialization response from $actorName: $other", isOptional)
      }(initTimeout)
    }

    commonCaps
  }

  protected def initFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
    if (isOptionalActor) {
      warning(msg)
    } else {
      error(msg)
      throw new RaceInitializeException(msg, cause.getOrElse(null))
    }
  }

  def createRemoteRaceContext(remoteUri: String): RaceContext = {
    val connectorName = "_connector_" + remoteUri.replace('/','!')
    val connectorRef = context.actorOf(Props(classOf[BusConnector], ras.bus), connectorName)
    val busIfc = new RemoteBusInterface(self, connectorRef)
    RaceContext(self,busIfc)
  }


  // check if this is an actor we know about (is part of our RAS)
  def isManaged (actorRef: ActorRef) = actors.contains(actorRef)

  // check if this is an actor we created
  def isSupervised (actorRef: ActorRef) = context.child(actorRef.path.name).isDefined


  //--- actor start

  protected def startFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
    if (isOptionalActor) {
      warning(msg)
    } else {
      error(msg)
      throw new RaceStartException(msg, cause.getOrElse(null))
    }
  }

  /**
    * this is called from receive and is not allowed to let exceptions pass
    */
  def onRaceStart = executeProtected {
    if (ras.isVerifiedSenderOf(RaceStart)) {
      val requester = sender
      try {
        simClock.resume
        startSatellites
        startRaceActors
        startHeartBeatMonitor
        requester ! RaceStarted
      } catch {
        case t: Throwable =>
          requester ! RaceStartFailed(t)
          // shutdown is initiated by requester
      }
    } else warning(s"RaceStart request from $sender ignored")
  }

  // TODO this should detect cycles
  def onRemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) = executeProtected {
    if (ras.isKnownRemoteMaster(remoteMaster)) {
      info(s"master $name got RemoteRaceStart at $simTime")
      simClock.reset(simTime,timeScale).resume
      startSatellites
      startRaceActors
      sender ! RaceStarted
    } else warning(s"RemoteRaceStart from unknown remote master: $remoteMaster")
  }

  def startRaceActors = {
    processChildren { actorData =>
      val actorRef = actorData.actorRef
      val actorConfig = actorData.config

      if (isSupervised(actorRef)) { // this does not include remote lookups
        val isOptional = isOptionalActor(actorConfig)
        val startTimeout = Timeout(actorConfig.getFiniteDurationOrElse("start-timeout", ras.defaultActorTimeout))

        info(s"sending StartRaceActor to ${actorRef.path.name}..")
        askForResult(actorRef ? StartRaceActor(self)) {
          case RaceActorStarted =>
            info(s"${actorRef.path.name} is running")
          case RaceActorStartFailed(reason) =>
            startFailed(s"start of ${actorRef.path.name} failed: $reason", isOptional)
          case TimedOut =>
            startFailed(s"starting ${actorRef.path} timed out", isOptional)
          case other => // illegal response
            startFailed(s"got unknown StartRaceActor response from ${actorRef.path.name}", isOptional)
        }(startTimeout)
      }
    }
  }

  def startSatellites = {
    ras.usedRemoteMasters.values.foreach { remoteMaster =>
      info(s"starting satellite ${remoteMaster.path}")
      askForResult( remoteMaster ? RemoteRaceStart(self, simClock.dateTime, simClock.timeScale)) {
        case RaceStarted => info(s"satellite started: ${remoteMaster.path}")
        case TimedOut => throw new RuntimeException(s"failed to start satellite ${remoteMaster.path}")
      }
    }
  }

  def startHeartBeatMonitor: Unit = {
    val heartBeat = ras.heartBeatInterval
    if (heartBeat.length > 0) {
      heartBeatScheduler = Some(context.system.scheduler.scheduleWithFixedDelay(Duration.Zero, heartBeat, self, HeartBeat))
    }
  }

  //--- actor pause

  def onRacePause = executeProtected {
    if (ras.commonCapabilities.supportsPauseResume){
      if (ras.isRunning) {
        info(s"master $name got RacePause, halting actors")
        pauseRaceActors
        sender ! RacePaused
      } else warning(s"master $name ignoring RacePause, system not running")
    } else warning(s"master $name ignoring RacePause, system does not support pause/resume")
  }

  protected def pauseRaceActors = {
    def pauseFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
      if (isOptionalActor) {
        warning(msg)
      } else {
        error(msg)
        throw new RacePauseException(msg, cause.getOrElse(null))
      }
    }

    processChildren { actorData =>
      val actorRef = actorData.actorRef
      val actorConfig = actorData.config
      val isOptional = isOptionalActor(actorConfig)
      val pauseTimeout = Timeout(actorConfig.getFiniteDurationOrElse("pause-timeout",ras.defaultActorTimeout))

      info(s"sending PauseRaceActor to ${actorRef.path.name}..")
      askForResult(actorRef ? PauseRaceActor(self)) {
        case RaceActorPaused =>
          info(s"${actorRef.path.name} is paused")
        case RaceActorPauseFailed(reason) =>
          pauseFailed(s"pause of ${actorRef.path.name} failed: $reason", isOptional)
        case TimedOut =>
          pauseFailed(s"pausing ${actorRef.path} timed out", isOptional)
        case other => // illegal response
          pauseFailed(s"got unknown PauseRaceActor response from ${actorRef.path.name}",isOptional)
      }(pauseTimeout)
    }
  }


  //--- actor resume

  def onRaceResume = executeProtected {
    if (ras.commonCapabilities.supportsPauseResume){
      if (ras.isPaused) {
        info(s"master $name got RaceResume, resuming actors")
        resumeRaceActors
        sender ! RaceResumed
      } else warning(s"master $name ignoring RaceResume, system not paused")
    } else warning(s"master $name ignoring RaceResume, system does not support pause/resume")
  }

  protected def resumeRaceActors = {
    def resumeFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
      if (isOptionalActor) {
        warning(msg)
      } else {
        error(msg)
        throw new RaceResumeException(msg, cause.getOrElse(null))
      }
    }

    processChildren { actorData =>
      val actorRef = actorData.actorRef
      val actorConfig = actorData.config
      val isOptional = isOptionalActor(actorConfig)
      val resumeTimeout = Timeout(actorConfig.getFiniteDurationOrElse("resume-timeout",ras.defaultActorTimeout))

      info(s"sending ResumeRaceActor to ${actorRef.path.name}..")
      askForResult(actorRef ? ResumeRaceActor(self)) {
        case RaceActorResumed =>
          info(s"${actorRef.path.name} is resumed")
        case RaceActorResumeFailed(reason) =>
          resumeFailed(s"resuming ${actorRef.path.name} failed: $reason", isOptional)
        case TimedOut =>
          resumeFailed(s"resuming ${actorRef.path} timed out", isOptional)
        case other => // illegal response
          resumeFailed(s"got unknown ResumeRaceActor response from ${actorRef.path.name}",isOptional)
      }(resumeTimeout)
    }
  }



  //--- actor termination

  private def shutdown = {
    terminateHeartBeat
    terminateSatellites
    terminateRaceActors
  }

  def onRaceTerminate = executeProtected {
    if (ras.isVerifiedSenderOf(RaceTerminate)) {
      info(s"master $name got RaceTerminate, shutting down")
      waiterForTerminated = Some(sender)
      shutdown

    } else warning(s"RaceTerminate request from $sender ignored")
  }

  def onRemoteRaceTerminate (remoteMaster: ActorRef) = executeProtected {
    if (ras.isKnownRemoteMaster(remoteMaster)) {
      if (ras.allowRemoteTermination) {
        info(s"master $name got RemoteRaceTerminate, shutting down")
        shutdown
      } else {
        warning(s"RemoteRaceTerminate from $remoteMaster ignored")
      }
      sender ! RaceTerminated
    } else warning(s"RemoteRaceTerminate from unknown remote master: $remoteMaster")
  }

  def terminateRaceActors: Unit = {
    terminateAndRemoveRaceActors

    waiterForTerminated match {
      case Some(actorRef) =>
        if (noMoreChildren) actorRef ! RaceTerminated
        else actorRef ! RaceTerminateFailed
      case None => // nothing to report
    }
  }

  def terminateSatellites = {
    ras.usedRemoteMasters.values.foreach { remoteMaster =>
      info(s"terminating satellite ${remoteMaster.path}")
      askForResult(remoteMaster ? RemoteRaceTerminate(self)) {
        case RaceTerminated =>
          info(s"got RaceTerminated from satellite ${remoteMaster.path}")
        case RaceAck =>
          info(s"got RaceAck termination response from satellite ${remoteMaster.path}")
        case TimedOut =>
          warning(s"satellite ${remoteMaster.path} termination timeout")
      }
    }
  }

  def terminateHeartBeat: Unit = {
    ifSome(heartBeatScheduler) { sched =>
      sched.cancel
      heartBeatScheduler = None
    }
  }

  def onRaceResetClock (originator: ActorRef, date: DateTime, tScale: Double) = {
    sendToChildren(SyncWithRaceClock)
  }

  def onHeartBeat: Unit = {
    if (ras.isLive){
      processRespondingChildren { actorData =>
        val actorRef = actorData.actorRef
        val lastPingTime = actorData.pingNanos
        if (lastPingTime > 0) { // did we already ping this actor?
          val pingResponse = actorData.receivedNanos
          if (pingResponse < lastPingTime) { // it did not respond to last ping
            // TODO - this should follow the supervisor policy, i.e. try to re-create the actor
            if (isOptionalActor(actorData.config)){
              warning(s"actor not responsive: ${actorRef.path}")
            } else {
              error(s"actor not responsive: ${actorRef.path}, shutting down")
              ThreadUtils.execAsync(ras.terminateActors)
            }
            context.stop(actorRef) // make sure Akka doesn't schedule this actor anymore
            actorData.isUnresponsive = true
          }
        }

        //--- record and send the next ping
        val now = System.nanoTime
        actorRef ! PingRaceActor(now)
        actorData.pingNanos = now
      }

      heartBeatCount += 1
      info(s"heartbeat: $heartBeatCount")
    }
  }

  def onPingRaceActorResponse (actorRef: ActorRef, msg: PingRaceActorResponse): Unit = {
    processChildRef(actorRef) { actorData=>
      actorData.receivedNanos = msg.receivedNanos
    }
  }

  def showActors (requester: ActorRef): Unit = {
    println(f"actors of system ${self.path}:")
    actors.foreach { actorData =>
      val actorRef = actorData.actorRef
      askForResult(actorRef ? ShowRaceActor(System.nanoTime)) {
        case ShowRaceActorResponse => // all Ok, next
        case TimedOut => println(f"  ${actorRef.path.name}%50s : UNRESPONSIVE")
        case other => println(f"  ${actorRef.path.name}%50s : wrong response ($other)")
      }
    }
    requester ! RaceShowCompleted
  }

  override def system: ActorSystem = ras.system
}