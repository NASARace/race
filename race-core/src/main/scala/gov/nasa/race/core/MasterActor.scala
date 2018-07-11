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

import java.lang.reflect.InvocationTargetException

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.pattern.AskSupport
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.util.StringUtils._
import org.joda.time.DateTime

import scala.collection.immutable.ListMap
import scala.collection.{Seq, mutable}
import scala.concurrent.SyncVar
import scala.concurrent.duration._
import scala.language.postfixOps


// these are only exchanged between masters during remote actor creation
// note - we can't use protected or objects because that would not match between different processes
case class RemoteConnectionRequest (requestingMaster: ActorRef)
case object RemoteConnectionAccept
case object RemoteConnectionReject

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
class MasterActor (ras: RaceActorSystem) extends Actor with ImplicitActorLogging with AskSupport {

  info(s"master created: ${self.path}")

  //--- convenience funcs
  def name = self.path.name
  def simClock = ras.simClock
  def actors = ras.actors
  def usedRemoteMasters = ras.usedRemoteMasters
  def actorRefs = ras.actors.keys
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

    case RaceCreate => onRaceCreate
    case RaceInitialize => onRaceInitialize
    case RaceStart => onRaceStart
    case RemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) => onRemoteRaceStart(remoteMaster,simTime,timeScale)

    case msg:SetLogLevel => actorRefs.foreach(_ ! msg) // just forward

    case RacePause => onRacePause
    case RaceResume => onRaceResume

    case RacePauseRequest =>
      info(s"master $name got RacePauseRequest from $sender")
      ras.requestPause(sender)

    case RaceResumeRequest =>
      info(s"master $name got RaceResumeRequest from $sender")
      ras.requestResume(sender)

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

    case Terminated(aref) => // TODO - how to react to death watch notification?

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

  //--- creation of RaceActors

  def onRaceCreate = executeProtected {
    if (ras.isVerifiedSenderOf(RaceCreate)) {
      try {
        createRaceActors
        sender ! RaceCreated // only gets sent out if there was no exception
      } catch {
        case x: Throwable => sender ! RaceCreateFailed(x)
      }
    } else {
      warning(s"RaceCreate request from $sender ignored")
    }
  }

  def createRaceActors = {
    ras.getActorConfigs.foreach(getActor)
  }

  def getActor (actorConfig: Config): Option[ActorRef] = {
    val actorName = actorConfig.getString("name")

    actorConfig.getOptionalString("remote") match {
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
        getRemoteActor(actorName,remoteUniverseName,remoteUri,actorConfig).map(registerRemoteActor(_,actorConfig))

      case None => // local actor
        instantiateLocalActor(actorConfig)
    }
  }

  def registerRemoteActor (aRef: ActorRef, actorConfig: Config): ActorRef = {
    askForResult( aRef ? RequestRaceActorCapabilities ) {
      case caps: RaceActorCapabilities =>
        ras.registerActor(aRef, actorConfig, caps)
        aRef
      case TimedOut =>
        error(s"request for remote actor capabilities timed out: $aRef")
        throw new RaceException("no actor capabilities")
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

  def getRemoteActor(actorName: String, remoteUniverseName: String, remoteUri: String, actorConfig: Config): Option[ActorRef] = {
    val path = s"$remoteUri/user/$remoteUniverseName/$actorName"
    info(s"looking up remote actor $path")
    val sel = context.actorSelection(path)
    askForResult(sel ? Identify(path)) {
      case ActorIdentity(path: String, r@Some(actorRef:ActorRef)) =>
        info(s"response from remote $path")
        context.watch(actorRef) // we can't supervise it anymore, but we can deathwatch
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

  def isOptionalActor (actorConfig: Config) = actorConfig.getBooleanOrElse("optional", false)

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
  protected def getActorProps (ctor: ()=>Actor, sync: SyncVar[Boolean]) = {
    Props( creator = {
      try {
        val a = ctor()
        sync.put(true)
        a
      } catch {
        case t: Throwable =>
          ras.reportException(t)
          sync.put(false)
          throw t
      }
    })
  }

  /**
    * this is the workhorse for actor construction
    * Note that it does NOT follow normal Akka convention because it has to construct
    * actors synchronously and tell us right away if there was an exception during construction.
    * We still need to be able to specify a supervisorStrategy though, i.e. we need to
    * instantiate through Props and from another thread.
    *
    * NOTE - since we use a SyncVar this does not work for remote actor creation
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
      val sync = new SyncVar[Boolean]
      val props = getActorProps(createActor,sync)

      val aref = context.actorOf(props,actorName) // note this executes the ctor in another thread

      // here we have to run counter to normal Akka best practices and block until we
      // either know the actor construction has succeeded, failed with an exception, or
      // failed with a timeout. In case of exceptions we want to return right away
      // All failure handling has to consider if the actor is optional or not
      val createTimeout = actorConfig.getFiniteDurationOrElse("create-timeout",ras.defaultActorTimeout)

      sync.get(createTimeout.toMillis) match {
        case Some(success) =>  // actor construction returned, but might have failed in ctor
          if (success) { // ctor success
            info(s"actor $actorName created")
            context.watch(aref) // start death watch
            Some(aref)

          } else { // ctor exception
            if (isOptionalActor(actorConfig)) {
              warning(s"optional actor construction caused exception $actorName")
              None
            } else {
              error(s"non-optional actor construction caused exception $actorName, escalating")
              throw new RaceInitializeException(s"failed to create actor $actorName")
            }
          }
        case None => // ctor timeout
          if (isOptionalActor(actorConfig)) {
            warning(s"optional actor construction timed out $actorName")
            None
          } else {
            error(s"non-optional actor construction timed out $actorName, escalating")
            throw new RaceInitializeException(s"failed to create actor $actorName")
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

  def getTimeout(conf: Config, key: String): Timeout = {
    if (conf.hasPath(key)) Timeout(conf.getFiniteDuration(key)) else timeout
  }

  //--- actor initialization

  protected def initFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
    if (isOptionalActor) {
      warning(msg)
    } else {
      error(msg)
      throw new RaceInitializeException(msg, cause.getOrElse(null))
    }
  }

  def onRaceInitialize = executeProtected {
    if (ras.isVerifiedSenderOf(RaceInitialize)) {
      val requester = sender // store before we send out / receive new messages
      try {
        initializeRaceActors
        sender ! RaceInitialized
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

  def initializeRaceActors = {
    for ((actorRef,actorConfig) <- actors){
      val actorName = actorRef.path.name
      val isOptional = isOptionalActor(actorConfig)
      val initTimeout = Timeout(actorConfig.getFiniteDurationOrElse("init-timeout",ras.defaultActorTimeout))

      val raceContext = actorConfig.getOptionalString("remote") match {
        case Some(remoteUri) => remoteContexts.getOrElse(remoteUri, createRemoteRaceContext(remoteUri))
        case None => localContext
      }

      info(s"sending InitializeRaceActor to $actorName")
      askForResult(actorRef ? InitializeRaceActor(raceContext, actorConfig)) {
        case RaceActorInitialized => info(s"received RaceInitialized from $actorName")
        case RaceActorInitializeFailed(reason) => initFailed(s"initialization of $actorName failed: $reason", isOptional)
        case TimedOut => initFailed(s"initialization timeout for $actorName", isOptional)
        case other => initFailed(s"invalid initialization response from $actorName: $other", isOptional)
      }(initTimeout)
      checkLiveness(actorRef)
    }
  }

  def checkLiveness (actorRef: ActorRef) = {
    askForResult( actorRef ? PingRaceActor()) {
      case PingRaceActor(tSent,tReceived) => // all Ok
      case TimedOut =>
        error(s"no PingRaceActor response from ${actorRef.path}, check handleMessage() for match-all clause")
        throw new RaceInitializeException(s"initialized ${actorRef.path} is unresponsive")
      case other =>
        error(s"invalid PingRaceActor response from ${actorRef.path}")
        throw new RaceInitializeException("invalid PingRaceActor response")
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
    for ((actorRef,actorConfig) <- actors){
      if (isSupervised(actorRef)) { // this does not include remote lookups
        val isOptional = isOptionalActor(actorConfig)
        val startTimeout = Timeout(actorConfig.getFiniteDurationOrElse("start-timeout",ras.defaultActorTimeout))

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

  //--- actor pause

  def onRacePause = executeProtected {
    if (ras.isVerifiedSenderOf(RacePause)) {
      info(s"master $name got RacePause, halting actors")
      pauseRaceActors
      sender ! RacePaused
    } else warning(s"RacePause request from $sender ignored")
  }

  def pauseRaceActors = {
    for ((actorRef,actorConfig) <- actors){
      val isOptional = isOptionalActor(actorConfig)
      val pauseTimeout = Timeout(actorConfig.getFiniteDurationOrElse("pause-timeout",ras.defaultActorTimeout))

      info(s"sending PauseRaceActor to ${actorRef.path.name}..")
      askForResult(actorRef ? PauseRaceActor(self)) {
        case RaceActorPaused =>
          info(s"${actorRef.path.name} is paused")
        case RaceActorPauseFailed(reason) =>
          startFailed(s"pause of ${actorRef.path.name} failed: $reason", isOptional)
        case TimedOut =>
          startFailed(s"pausing ${actorRef.path} timed out", isOptional)
        case other => // illegal response
          startFailed(s"got unknown PauseRaceActor response from ${actorRef.path.name}",isOptional)
      }(pauseTimeout)
    }
  }

  protected def pauseFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
    if (isOptionalActor) {
      warning(msg)
    } else {
      error(msg)
      throw new RacePauseException(msg, cause.getOrElse(null))
    }
  }

  //--- actor resume

  def onRaceResume = executeProtected {
    if (ras.isVerifiedSenderOf(RaceResume)) {
      info(s"master $name got RaceResume, resuming actors")
      resumeRaceActors
      sender ! RaceResumed
    } else warning(s"RaceResume request from $sender ignored")
  }

  def resumeRaceActors = {
    for ((actorRef,actorConfig) <- actors){
      val isOptional = isOptionalActor(actorConfig)
      val resumeTimeout = Timeout(actorConfig.getFiniteDurationOrElse("resume-timeout",ras.defaultActorTimeout))

      info(s"sending ResumeRaceActor to ${actorRef.path.name}..")
      askForResult(actorRef ? ResumeRaceActor(self)) {
        case RaceActorResumed =>
          info(s"${actorRef.path.name} is resumed")
        case RaceActorResumeFailed(reason) =>
          startFailed(s"resuming ${actorRef.path.name} failed: $reason", isOptional)
        case TimedOut =>
          startFailed(s"resuming ${actorRef.path} timed out", isOptional)
        case other => // illegal response
          startFailed(s"got unknown ResumeRaceActor response from ${actorRef.path.name}",isOptional)
      }(resumeTimeout)
    }
  }

  protected def resumeFailed(msg: String, isOptionalActor: Boolean, cause: Option[Throwable]=None) = {
    if (isOptionalActor) {
      warning(msg)
    } else {
      error(msg)
      throw new RaceResumeException(msg, cause.getOrElse(null))
    }
  }

  //--- actor termination

  private def shutdown = {
    terminateRaceActors
    terminateSatellites
  }

  def onRaceTerminate = executeProtected {
    if (ras.isVerifiedSenderOf(RaceTerminate)) {
      info(s"master $name got RaceTerminate, shutting down")
      shutdown
      sender ! RaceTerminated
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

  def terminateRaceActors = { // terminate in reverse order of creation
    setUnrespondingTerminatees( actors.foldRight (Seq.empty[(ActorRef,Config)]) { (e, leftOverActors) =>
      val (actorRef,actorConfig) = e
      info(s"sending TerminateRaceActor to ${actorRef.path}")
      askForResult(actorRef ? TerminateRaceActor(self)) {
        case RaceActorTerminated => // all fine, actor did shut down
          info(s"got RaceActorTerminated from ${actorRef.path.name}")
          stopRaceActor(actorRef) // stop it so that name becomes available again
          leftOverActors
        case RaceActorTerminateIgnored =>
          info(s"got RaceActorTerminateIgnored from ${actorRef.path.name}")
          leftOverActors

          //--- failures
        case RaceActorTerminateFailed(reason) =>
          warning(s"RaceActorTerminate of ${actorRef.path.name} failed: $reason")
          (actorRef -> actorConfig) +: leftOverActors
        case TimedOut =>
          warning(s"no TerminateRaceActor response from ${actorRef.path.name}")
          (actorRef -> actorConfig) +: leftOverActors
        case other => // illegal response
          warning(s"got unknown TerminateRaceActor response from ${actorRef.path.name}")
          (actorRef -> actorConfig) +: leftOverActors
      }
    })
  }

  def setUnrespondingTerminatees(unresponding: Seq[(ActorRef,Config)]): Unit = {
    ras.actors = ListMap(unresponding:_*)
  }

  def stopRaceActor (actorRef: ActorRef): Unit =  {
    context.stop(actorRef) // finishes current and discards all pending, then calls postStop
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

  def onRaceResetClock (originator: ActorRef, date: DateTime, tScale: Double) = {
    actors.foreach( _._1 ! SyncWithRaceClock)
  }
}