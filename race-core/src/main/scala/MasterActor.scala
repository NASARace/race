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
import gov.nasa.race.common._
import gov.nasa.race.common.StringUtils._
import gov.nasa.race.common.NetUtils._
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core.Messages._
import org.joda.time.DateTime

import scala.collection.immutable.ListMap
import scala.collection.{Seq, mutable}
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
  */
class MasterActor (ras: RaceActorSystem) extends Actor with ImplicitActorLogging {

  info(s"master created: ${self.path}")

  //--- convenience funcs
  def name = self.path.name
  def simClock = ras.simClock
  def actors = ras.actors
  def satellites = ras.satellites
  def actorRefs = ras.actors.keys
  def localContext = ras.localRaceContext

  //--- creation of RaceActors

  def createRaceActors = {
    try {
      ras.actors = ras.getActorConfigs.foldLeft(ras.actors) { (map, actorConfig) =>
        getActor(actorConfig) match {
          case Some(actorRef) => map + (actorRef -> actorConfig)
          case None => map
        }
      }
    } catch {
      case x: ClassNotFoundException =>
        log.error(s"master could not find class: ${x.getMessage}")
        throw new RaceInitializeException(x.getMessage)
    }
  }

  def getActor (actorConfig: Config): Option[ActorRef] = {
    val actorName = actorConfig.getString("name")
    actorConfig.getOptionalString("remote") match {
      case Some(remoteUri) =>
        val isOptional = actorConfig.getBooleanOrElse("optional", false)
        val remoteUniverseName = userInUrl(remoteUri).get
        if (satellites.get(remoteUri).isEmpty) {
          if (isConflictingHost(remoteUri)){
            if (isOptional) {
              warning(s"ignoring $actorName from conflicting host $remoteUri")
              return None
            }
            else throw new RaceException("conflicting host of $remoteUri")
          } else {
            lookupRemoteMaster(remoteUniverseName, remoteUri, isOptional) match {
              case Some(remoteMasterRef) => ras.satellites = satellites + (remoteUri -> remoteMasterRef)
              case None => return None // no (optional) satellite, nothing to look up or start
            }
          }
        }
        getRemoteActor(actorName,remoteUniverseName,remoteUri,actorConfig)
      case None => // local actor
        instantiateActor(actorConfig)
    }
  }

  def isConflictingHost (remoteUri: String): Boolean = {
    val loc = stringTail(remoteUri,'@')
    satellites.exists( e => loc == stringTail(e._1,'@'))
  }

  def lookupRemoteMaster (remoteUniverseName: String, remoteUri: String, isOptional: Boolean): Option[ActorRef] = {
    val path = s"$remoteUri/user/$remoteUniverseName"
    info(s"looking up remote actor system $path")
    val sel = context.actorSelection(path)
    askForResult (sel ? Identify(path)) {
      case ActorIdentity(path: String, Some(actorRef:ActorRef)) =>
        info(s"got master response from remote actor system: ${actorRef.path}")
        Some(actorRef)
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

  def getRemoteActor(actorName: String, remoteUniverseName: String, remoteUri: String, actorConfig: Config): Option[ActorRef] = {
    val path = s"$remoteUri/user/$remoteUniverseName/$actorName"
    info(s"looking up remote actor $path")
    val sel = context.actorSelection(path)
    askForResult(sel ? Identify(path)) {
      case ActorIdentity(path: String, r@Some(actorRef:ActorRef)) =>
        info(s"response from remote $path")
        context.watch(actorRef) // we can't supervise it anymore, but we can deathwatch
        r
      case ActorIdentity(path: String, None) =>
        if (actorConfig.hasPath("class")) {
          instantiateActor(actorConfig) // create it
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

  def isOptionalActor (actorConfig: Config) = actorConfig.getBooleanOrElse("optional", false)

  // NOTE - the constructor of the actor to instantiate executes in another thread
  // in order to stay synchronous, we have to wait for the constructor to return
  def instantiateActor (actorConfig: Config): Option[ActorRef] = {
    val actorName = actorConfig.getString("name")
    val clsName = actorConfig.getClassName("class")
    val actorCls = ras.classLoader.loadClass(clsName)

    info(s"creating $actorName ..")
    val aref = try {
      actorCls.getConstructor(classOf[Config])
      context.actorOf(Props(actorCls, actorConfig), actorName)
    } catch {
      case _: java.lang.NoSuchMethodException =>
        actorCls.getConstructor()
        context.actorOf(Props(actorCls), actorName)
    }

    waitForActor(aref) {
      case NotFound =>
        if (isOptionalActor(actorConfig)) {
          warning(s"optional actor did not instantiate $actorName")
        } else {
          error(s"non-optional actor did not instantiate $actorName")
          throw new RaceInitializeException(s"failed to create actor $actorName")
        }
      case other:AskFailure =>
        error(s"failed actor instantiation: $other")
        throw new RaceInitializeException(s"failed to create actor $actorName")
    }
  }

  //--- actor initialization

  // cache to be only used during actor initialization
  protected val remoteContexts = mutable.Map.empty[UrlString,RaceContext]

  def initializeRaceActors = {
    ras.actors.foreach { e =>
      val (actorRef, actorConfig) = e
      val actorName = actorConfig.getString("name")

      val raceContext = actorConfig.getOptionalString("remote") match {
        case Some(remoteUri) => remoteContexts.getOrElse(remoteUri, createRemoteRaceContext(remoteUri))
        case None => localContext
      }

      info(s"sending InitializeRaceActor to $actorName")
      askForResult(actorRef ? InitializeRaceActor(raceContext, actorConfig)) {
        case RaceActorInitialized => // Ok, this one is ready
          info(s"received RaceInitialized from $actorName")
        case RaceActorInitializeFailed(reason) =>
          error(s"initialization of $actorName failed: $reason")
          // <2do> should use configurable policy here
          throw new RaceInitializeException("InitializeRaceActor failed")
        case TimedOut =>
          error(s"initialization timeout for $actorName")
          throw new RaceInitializeException("InitializeRaceActor response timeout")
        case other =>
          error(s"invalid initialization response from $actorName: $other")
          throw new RaceInitializeException("invalid InitializeRaceActor response")
      }
    }
  }

  def createRemoteRaceContext(remoteUri: String): RaceContext = {
    val connectorName = "_connector_" + remoteUri.replace('/','!')
    val connectorRef = context.actorOf(Props(classOf[BusConnector], ras.bus), connectorName)
    val busIfc = new RemoteBusInterface(self, connectorRef)
    RaceContext(self,busIfc)
  }

  def startSatellites = {
    ras.satellites.values.foreach { remoteMaster =>
      info(s"starting satellite ${remoteMaster.path}")
      askForResult( remoteMaster ? StartSimClock( simClock.dateTime, simClock.timeScale)) {
        case RaceAck => info(s"set satellite clock: ${remoteMaster.path}")
        case TimedOut => throw new RuntimeException("failed to set satellite clock")
      }
      askForResult( remoteMaster ? RaceStart) {
        case RaceStarted => info(s"satellite started: ${remoteMaster.path}")
        case TimedOut => throw new RuntimeException("failed to start satellite")
      }
    }
  }

  // check if this is an actor we know about (is part of our RAS)
  def isManaged (actorRef: ActorRef) = actors.contains(actorRef)

  // check if this is an actor we created
  def isSupervised (actorRef: ActorRef) = context.child(actorRef.path.name).isDefined


  //--- actor start

  def startRaceActors = {
    for ((actorRef,actorConfig) <- actors){
      if (isSupervised(actorRef)) { // this does not include remote lookups
        info(s"sending StartRaceActor to ${actorRef.path.name}..")
        askForResult(actorRef ? StartRaceActor(self)) {
          case RaceActorStarted =>
            info(s"${actorRef.path.name} is running")
          case RaceActorStartFailed(reason) =>
            error(s"start of ${actorRef.path.name} failed: $reason")
            throw new RaceStartException("StartRaceActor failed")
          case TimedOut =>
            // <2do> this should escalate based on if actor is optional, local/remote
            warning(s"starting ${actorRef.path} timed out")
          case other => // illegal response
            warning(s"got unknown StartRaceActor response from ${actorRef.path.name}")
        }
      }
    }
  }

  //--- actor termination

  def terminateSatellites = {
    ras.satellites.values.foreach { remoteMaster =>
      info(s"terminating satellite ${remoteMaster.path}")
      askForResult(remoteMaster ? RaceTerminate) {
        case RaceTerminated =>
          info(s"got RaceTerminated from satellite ${remoteMaster.path}")
        case RaceAck =>
          info(s"got RaceAck termination response from satellite ${remoteMaster.path}")
        case TimedOut =>
          warning(s"satellite ${remoteMaster.path} termination timeout")
      }
    }
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

  //--- RAS message processing - make sure these really came from our RAS

  override def receive: Receive = {
    case q @ RaceCreate if ras.isVerifiedSenderOf(q) =>
      createRaceActors
      sender ! RaceCreated

    case q @ RaceInitialize if ras.isVerifiedSenderOf(q) =>
      initializeRaceActors
      sender ! RaceInitialized

    case q @ RaceStart if ras.isVerifiedSenderOf(q) =>
      simClock.resume
      startSatellites
      startRaceActors
      sender ! RaceStarted

    case q @ RaceTerminate if ras.isVerifiedSenderOf(q) =>
      info(s"master $name got RaceTerminate, shutting down")
      terminateRaceActors
      terminateSatellites
      sender ! RaceTerminated

    case RaceTerminateRequest => // from some actor, let the RAS decide
      info(s"master $name got RaceTerminateRequest")
      ras.terminationRequest(sender)


    //--- time management
    case StartSimClock(dateTime: DateTime, timeScale: Double) =>
      simClock.reset(dateTime,timeScale).resume
      sender ! RaceAck
    case SyncSimClock(dateTime: DateTime, timeScale: Double) =>
      simClock.reset(dateTime,timeScale)
      sender ! RaceAck
    case StopSimClock =>
      simClock.stop
      sender ! RaceAck
    case ResumeSimClock =>
      simClock.resume
      sender ! RaceAck
  }

}