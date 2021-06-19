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
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.util.StringUtils._
import gov.nasa.race.util.ThreadUtils._

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.language.postfixOps

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
class MasterActor (ras: RaceActorSystem) extends Actor with ParentActor with MonitorActor {

  info(s"master created: ${self.path}")

  var waiterForTerminated: Option[ActorRef] = None

  //--- convenience funcs
  def name = self.path.name
  def simClock = ras.simClock
  def usedRemoteMasters = ras.usedRemoteMasters
  def localContext = ras.localRaceContext
  override def system: ActorSystem = ras.system

  def config = ras.config

  //context.system.eventStream.subscribe(self, classOf[DeadLetter])

  def executeProtected (f: => Unit): Unit = {
    try f catch {
      case x: Throwable => error(s"master caught exception in receive(): $x")
    }
  }

  def isLegitimateRequest(msg: Any): Boolean = ras.isVerifiedSenderOf(msg) || ras.isKnownRemoteMaster(sender())

  /**
    *   master message processing
    *   verify that critical messages come from a legit source (our RAS or remote master), and
    *   make sure we catch any exceptions - the master is the one actor that is not allowed to crash, it
    *   has to stay responsive
    */
  def handleMasterMessages: Receive = {
    case RemoteConnectionRequest(remoteMaster) => onRemoteConnectionRequest(remoteMaster)

    case RaceCreate => onRaceCreate
    case RaceInitialize => onRaceInitialize
    case RaceStart => onRaceStart
    case RemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) => onRemoteRaceStart(remoteMaster,simTime,timeScale)

    case msg:SetLogLevel => sendToChildren(msg) // just forward

    case RacePauseRequest => onRacePauseRequest(sender())
    case RacePause => onRacePause(sender())

    case RaceResumeRequest => onRaceResumeRequest(sender())
    case RaceResume => onRaceResume(sender())

    case RaceTerminateRequest => onRaceTerminateRequest(sender())
    case RaceTerminate => onRaceTerminate
    case RemoteRaceTerminate (remoteMaster: ActorRef) => onRemoteRaceTerminate(remoteMaster)

    case rrc@RaceResetClock(originator,d,tScale) =>
      info(s"master $name got $rrc")
      onRaceResetClock(originator,d,tScale) // RAS changed simClock, inform actors

    //--- inter RAS time management
    case RemoteClockReset (date,timeScale) => onRemoteClockReset(sender(), date, timeScale)

      // TODO - this needs to be unified with SyncWithRaceClock
    case SyncSimClock(dateTime: DateTime, timeScale: Double) =>
      simClock.reset(dateTime,timeScale)
      sender() ! RaceAck
    case StopSimClock =>
      simClock.stop
      sender() ! RaceAck
    case ResumeSimClock =>
      simClock.resume
      sender() ! RaceAck

    case Terminated(actorRef) => // Akka death watch event
      removeChildActorRef(actorRef)

    case RaceActorStopped() => // from postStop hook of RaceActors
     stoppedChildActorRef(sender())

    case RaceShow => report(System.out, false) // print actor tree

    case deadLetter: DeadLetter => // TODO - do we need to react to DeadLetters?
  }

  def receive: Receive = handleMasterMessages.orElse( handleActorMonitorMessages)

  //--- failure strategy

  override val supervisorStrategy = ras.defaultSupervisorStrategy

  //--- remote connection management

  def onRemoteConnectionRequest(remoteMaster: ActorRef) = executeProtected {
    info(s"received remote connection request from: ${remoteMaster.path}")
    if (ras.acceptsRemoteConnectionRequest(remoteMaster)) {
      info(s"accepted remote connection request from: ${remoteMaster.path}")
      sender() ! RemoteConnectionAccept(ras.localCapabilities)
    } else {
      warning(s"rejected remote connection request from: ${remoteMaster.path}")
      sender() ! RemoteConnectionReject()
    }
  }

  def onRemoteClockReset (requester: ActorRef, date: DateTime, timeScale: Double): Unit = {
    ras.requestSimClockReset(requester,date,timeScale)
  }

  //--- aux functions

  def isOptionalActor (actorConfig: Config) = actorConfig.getBooleanOrElse("optional", false)

  def getTimeout(conf: Config, key: String): Timeout = {
    if (conf.hasPath(key)) Timeout(conf.getFiniteDuration(key)) else timeout
  }

  //--- creation of RaceActors

  // this is in an ask pattern sent from the RAS
  def onRaceCreate = executeProtected {
    val originator = sender()

    if (ras.isVerifiedSenderOf(RaceCreate)) {
      try {
        ras.getActorConfigs.foreach { actorConf =>
          val actorName = actorConf.getString("name")
          val isOptional = actorConf.getBooleanOrElse("optional", false)

          getActor(actorConf, isOptional) match {
            case Some(actorRef) =>
              addChildActorRef(actorRef,actorConf)
              context.watch(actorRef)
            case None =>
              if (!isOptional) {
                error(s"creation of $actorName failed without exception")
                originator ! RaceCreateFailed(s"actor construction failed without exception: $actorName")
              } else {
                warning(s"ignoring missing optional $actorName")
              }
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

  def getActor (actorConfig: Config, isOptional: Boolean): Option[ActorRef] = {
    val actorName = actorConfig.getString("name")

    actorConfig.getOptionalString("remote") match {

      case None => // local actor, the dominant case
        instantiateLocalActor(actorConfig)

      case Some(remoteUri) => // remote actor
        val remoteUniverseName = userInUrl(remoteUri).get
        if (usedRemoteMasters.get(remoteUri).isEmpty) {
          if (isConflictingHost(remoteUri)){
            if (isOptional) {
              warning(s"ignoring $actorName from conflicting host $remoteUri")
              return None
            }
            else throw new RaceException("conflicting host of $remoteUri")
          } else {
            lookupRemoteMaster( remoteUniverseName, remoteUri, isOptional) match {
              case Some((remoteMasterRef,caps)) => ras.addUsedRemote( remoteUri, remoteMasterRef, caps)
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

  def lookupRemoteMaster (remoteUniverseName: String, remoteUri: String, isOptional: Boolean): Option[(ActorRef,RaceActorCapabilities)] = {
    val path = s"$remoteUri/user/$remoteUniverseName"
    info(s"looking up remote actor system $path")
    val sel = context.actorSelection(path)
    // two step process: (1) obtain the reference for a remote master, (2) p2p check if it is accepting the connection
    askForResult (sel ? Identify(path)) {
      case ActorIdentity(path: String, Some(actorRef:ActorRef)) =>
        info(s"found master for remote actor system: ${actorRef.path}")
        checkRemoteConnectionAccept(actorRef) match {
          case Some(caps) =>
            info(s"remote master accepted connection request: ${actorRef.path} with caps: $caps")
            Some(actorRef -> caps)
          case None =>
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

  def checkRemoteConnectionAccept(remoteMaster: ActorRef): Option[RaceActorCapabilities] = {
    askForResult (remoteMaster ? RemoteConnectionRequest(self)) {
      case RemoteConnectionAccept(caps) => Some(caps)
      case RemoteConnectionReject() => None
      case TimedOut => None
    }
  }

  //--- actor initialization

  def onRaceInitialize = executeProtected {
    if (ras.isVerifiedSenderOf(RaceInitialize)) {
      val requester = sender() // store before we send out / receive new messages
      try {
        val commonCaps = initializeRaceActors
        sender() ! RaceInitialized(commonCaps)
      } catch {
        case x: Throwable =>
          //x.printStackTrace // TODO - exceptions should be loggable to a file
          requester ! RaceInitializeFailed(x)
        // shutdown is initiated by the sender
      }
    } else warning(s"RaceInitialize request from ${sender()} ignored")
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

          // note this does not start monitoring yet, just registers the actor and its automatically created children
          // actor should at this point have created its stable child actors
          registerMonitoredActor(ras.pathPrefix, actorRef)

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
      val requester = sender()
      try {
        simClock.resume
        startSatellites
        startRaceActors
        requester ! RaceStarted()
      } catch {
        case t: Throwable =>
          requester ! RaceStartFailed(t)
          // shutdown is initiated by requester
      }
    } else warning(s"RaceStart request from ${sender()} ignored")
  }

  /**
    * notification that a remote RAS that uses local actors via lookup has started
    */
  def onRemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) = executeProtected {
    // TODO this should detect cycles
    if (ras.isKnownRemoteMaster(remoteMaster)) {
      info(s"master $name got RemoteRaceStart at $simTime")
      simClock.reset(simTime,timeScale).resume
      startSatellites
      startRaceActors
      sender() ! RaceStarted()
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
          case RaceActorStarted() =>
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

    startMonitoring()
  }

  def startSatellites = {
    ras.usedRemoteMasters.values.foreach { remoteMaster =>
      info(s"starting satellite ${remoteMaster.path}")
      askForResult( remoteMaster ? RemoteRaceStart(self, simClock.dateTime, simClock.timeScale)) {
        case RaceStarted() => info(s"satellite started: ${remoteMaster.path}")
        case TimedOut => throw new RuntimeException(s"failed to start satellite ${remoteMaster.path}")
      }
    }
  }

  //--- actor pause

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
        case RaceActorPaused() =>
          //info(s"${actorRef.path.name} is paused")
        case RaceActorPauseFailed(reason) =>
          pauseFailed(s"pause of ${actorRef.path.name} failed: $reason", isOptional)
        case TimedOut =>
          pauseFailed(s"pausing ${actorRef.path} timed out", isOptional)
        case other => // illegal response
          pauseFailed(s"got unknown PauseRaceActor response from ${actorRef.path.name}: $other",isOptional)
      }(pauseTimeout)
    }
  }

  def onRacePause (originator: ActorRef) = executeProtected {
    if (ras.localCapabilities.supportsPauseResume){
      if (ras.isRunning) {
        info(s"master $name got RacePause, halting actors")
        try {
          pauseRaceActors
          ras.setPaused
          originator ! RacePaused()
        } catch {
          case x: RacePauseException =>
            originator ! RacePauseFailed(x.getMessage)
        }
      } else warning(s"master $name ignoring RacePause, system not running")
    } else warning(s"master $name ignoring RacePause, system does not support pause/resume")
  }

  /**
    * RequestPause response - note this has to come from a registered toplevel actor
    */
  protected def onRacePauseRequest(originator: ActorRef): Unit = {
      if (ras.isRunning){
        if (isManaged(originator)) {
          if (ras.localCapabilities.supportsPauseResume){
            onRacePause(originator)
          } else warning("ignoring pause request: universe does not support pause/resume")
        } else warning(s"ignoring pause request: unknown actor ${originator.path}")
      } else warning("ignoring pause request: universe not running")
  }

  //--- actor resume

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
        case RaceActorResumed() =>
          //info(s"${actorRef.path.name} is resumed")
        case RaceActorResumeFailed(reason) =>
          resumeFailed(s"resuming ${actorRef.path.name} failed: $reason", isOptional)
        case TimedOut =>
          resumeFailed(s"resuming ${actorRef.path} timed out", isOptional)
        case other => // illegal response
          resumeFailed(s"got unknown ResumeRaceActor response from ${actorRef.path.name}: $other",isOptional)
      }(resumeTimeout)
    }
  }

  def onRaceResume (originator: ActorRef) = executeProtected {
    if (ras.localCapabilities.supportsPauseResume){
      if (ras.isPaused) {
        info(s"master $name got RaceResume, resuming actors")
        try {
          resumeRaceActors
          ras.setResumed
          originator ! RaceResumed()
        } catch {
          case x: RaceResumeException =>
            originator ! RaceResumeFailed(x.getMessage)
        }
      } else warning(s"master $name ignoring RaceResume, system not paused")
    } else warning(s"master $name ignoring RaceResume, system does not support pause/resume")
  }

  /**
    * RequestResume response - note this has to come from a registered toplevel actor
    */
  protected def onRaceResumeRequest(originator: ActorRef): Unit = {
    if (ras.isPaused){
      if (isManaged(originator)) {
        if (ras.localCapabilities.supportsPauseResume){
          onRaceResume(originator)
        } else warning("ignoring resume request: universe does not support pause/resume")
      } else warning(s"ignoring resume request: unknown actor ${originator.path}")
    } else warning("ignoring resume request: universe not paused")
  }

  //--- actor termination

  private def shutdown = {
    stopMonitoring()
    terminateRaceActors()
    terminateSatellites()
  }

  def onRaceTerminate = executeProtected {
    if (ras.isVerifiedSenderOf(RaceTerminate)) {
      info(s"master $name got RaceTerminate, shutting down")
      waiterForTerminated = Some(sender())
      shutdown

    } else warning(s"RaceTerminate request from ${sender()} ignored")
  }

  def onRemoteRaceTerminate (remoteMaster: ActorRef) = executeProtected {
    if (ras.isKnownRemoteMaster(remoteMaster)) {
      if (ras.allowRemoteTermination) {
        info(s"got RemoteRaceTerminate from $remoteMaster, shutting down")
        ras.requestTermination(remoteMaster)
      } else {
        info(s"got RemoteRaceTerminate from $remoteMaster but remote termination not configured")
      }
      sender() ! RaceTerminated()
    } else warning(s"RemoteRaceTerminate from unknown remote master $remoteMaster ignored")
  }

  def terminateRaceActors(): Unit = {
    terminateAndRemoveRaceActors(ras.terminateTimeout)

    waiterForTerminated match {
      case Some(actorRef) =>
        if (noMoreChildren) actorRef ! RaceTerminated()
        else actorRef ! RaceTerminateFailed
      case None => // nothing to report
    }
  }

  def terminateSatellites() = {
    ras.usedRemoteMasters.values.foreach { remoteMaster =>
      info(s"terminating satellite ${remoteMaster.path}")
      askForResult(remoteMaster ? RemoteRaceTerminate(self)) {
        case RaceTerminated() =>
          info(s"got RaceTerminated from satellite ${remoteMaster.path}")
        case RaceAck() =>
          info(s"got RaceAck termination response from satellite ${remoteMaster.path}")
        case TimedOut =>
          warning(s"satellite ${remoteMaster.path} termination timeout")
      }
    }
  }

  def onRaceTerminateRequest (originator: ActorRef): Unit = {
    if (ras.isLive) {
      if (isManaged(originator)) {
        ras.requestTermination(originator)
      } else warning(s"ignoring terminate request: unknown actor ${originator.path}")
    } else warning("ignoring termination request: universe not live")
  }


  def onRaceResetClock (originator: ActorRef, date: DateTime, tScale: Double) = {
    sendToChildren(SyncWithRaceClock())
  }

}