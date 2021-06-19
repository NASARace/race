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

import akka.actor.SupervisorStrategy.{Resume, Stop}

import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeoutException
import akka.actor._
import akka.event.Logging.LogLevel
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.SettableClock
import gov.nasa.race.common.Status._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.ConsoleIO.AppMenu
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.util.NetUtils._
import gov.nasa.race.util.{ClassLoaderUtils, ThreadUtils}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.{ListMap, Map, Set}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.language.postfixOps


object RaceActorSystem { // aka RAS

  private val liveSystems = TrieMap[ActorSystem, RaceActorSystem]()
  // all RaceActors live in RaceActorSystems, no need to use Option
  def apply(system: ActorSystem): RaceActorSystem = liveSystems.get(system).get

  var terminationListeners = Set.empty[() => Unit]
  def addTerminationListener(listener: () => Unit) = terminationListeners = terminationListeners + listener
  def addTerminationAction(action: => Unit) = terminationListeners = terminationListeners + (() => action)
  def removeTerminationListener(listener: () => Unit) = terminationListeners = terminationListeners - listener
  def removeAllTerminationListeners = terminationListeners = Set.empty[() => Unit]

  def hasLiveSystems = liveSystems.nonEmpty
  def isTerminating = liveSystems.nonEmpty && liveSystems.exists(e=> e._2.isTerminating)
  def numberOfLiveSystems = liveSystems.size
  def addLiveSystem(race: RaceActorSystem) = liveSystems += (race.system -> race)
  def removeLiveSystem(race: RaceActorSystem) = {
    liveSystems -= race.system
    if (liveSystems.isEmpty) {
      // we need to do this sync since the logging might not show anymore
      println("last actor system did shut down, exiting RACE\n")
      terminationListeners.foreach(_())

      if (!isRunningEmbedded) System.exit(0)
    }
  }

  def shutdownLiveSystems = {
    liveSystems.values.foreach(_.terminateActors)
  }

  private var isRunningEmbedded = false
  def runEmbedded = isRunningEmbedded = true
}

/**
 * RaceActorSystem (RAS) instances represent a Akka actor system comprised of RaceActors, managed
 * by a single (non-RaceActor) Master, communicating through a Bus that allows for local and
 * remote publish/subscribe.
 *
 * RaceActorSystems are instantiated by the respective Main, providing a Config object
 * specifying its RaceActors.
 *
 * At runtime, a RAS is mostly used to aggregate information that is needed by its
 * RaceActors, i.e. it doesn't play an active role except of controlling termination
 * policies.
 *
 * NOTE - the RaceActorSystem instance is shared between all local actors. Make sure
 * this doesn't create race conditions (exposed data should be invariant after init,
 * or at least thread safe)
 *
 * We can't easily turn this into a Akka extension since we have to be in control of
 * when&where RAS instances are created. The RAS owns the Akka 'system', not the other way
 */
class RaceActorSystem(val config: Config) extends LogController with VerifiableAsker {
  import gov.nasa.race.core.RaceActorSystem._

  protected var status = Initializing
  val name = config.getString("name")
  val system = createActorSystem(name, config)
  implicit val log = getLoggingAdapter(system)
  val classLoader = ClassLoaderUtils.setRaceClassloader(system, config.getOptionalString("classpath"))
  val bus = createBus(system)

  val pathPrefix = s"/$name"

  //--- clock initialization and delays
  val simClock = createSimClock
  var wallStartTime = wallClockStartTime(false) // will be re-evaluated during launch
  val delayLaunch = config.getBooleanOrElse("delay-launch", false)

  //--- specific timeouts
  val defaultSystemTimeout: FiniteDuration = config.getFiniteDurationOrElse("system-timeout",timeout.duration*2)
  val defaultActorTimeout: FiniteDuration = config.getFiniteDurationOrElse("actor-timeout",timeout.duration)
  val createTimeout = Timeout(config.getFiniteDurationOrElse("create-timeout", defaultSystemTimeout))
  val initTimeout = Timeout(config.getFiniteDurationOrElse("init-timeout", defaultSystemTimeout))
  val startTimeout = Timeout(config.getFiniteDurationOrElse("start-timeout", defaultSystemTimeout))
  val terminateTimeout = Timeout(config.getFiniteDurationOrElse("terminate-timeout", 30.seconds))

  // do we allow external (remote) termination
  val allowRemoteTermination = config.getBooleanOrElse("remote-termination", false)
  // do we allow our own actors to trigger termination
  val allowSelfTermination = config.getBooleanOrElse("self-termination", true)

  val showExceptions = config.getBooleanOrElse("show-exceptions",false)

  // optional configured app menu for this actor system
  val appMenu: Option[AppMenu] = {
    val cmdSpecs = config.getConfigSeq("menu")
    if (cmdSpecs.nonEmpty) Some(new AppMenu(this, cmdSpecs)) else None
  }

  // remote RAS book keeping
  var usedRemoteMasters = Map.empty[UrlString, ActorRef] // the remote masters we use (via our remote actors)
  var usingRemoteMasters = Set.empty[ActorRef] // the remote masters that use us (some of our actors are their remotes)

  // updated during initialization with caps that are supported by all actors (intersection)
  var localCapabilities = RaceActorCapabilities.AllCapabilities
  var remoteCapabilities = RaceActorCapabilities.AllCapabilities

  loadSystemProperties // (some of) which can be directly set from the config

  addLiveSystem(this)
  system.whenTerminated.foreach( _ => removeLiveSystem(this))

  RaceLogger.logController = this
  debug(s"initializing RaceActorSystem for config:\n${showConfig(config)})")

  // the one actor to rule them all
  val master = system.actorOf(Props(getMasterClass, this), name)
  waitForActor(master) {
    case e =>
      error(s"error instantiating master of $name: $e")
      throw new RaceInitializeException("no master for $name")
  }

  // this needs to be here so that all local actors can get it (before they get created!)
  val localRaceContext = createRaceContext(master, bus)

  createActors
  if (status == Created) initializeActors

  if (status != Initialized) {
    system.terminate()
    throw new RaceInitializeException("race actor system did not initialize")
  }

  // from here on the driver takes over and (at some point) calls startActors

  //--- end of initialization

  def loadSystemProperties: Unit = {
    // load from file
    ifSome(config.getOptionalFile("properties")){ propFile =>
      using(new FileInputStream(propFile)) { fis => System.getProperties.load(fis) }
    }

    // now check if we have explicitly set properties
    ifSome(config.getOptionalConfig("property")) { pconf =>
      pconf.entrySet.asScala.foreach{ e=> System.setProperty(e.getKey, e.getValue.unwrapped.toString) }
    }
  }

  /**
    * either schedule the start if there is a configured start time, or start right away if not
    *
    * @return true if status is started
    */
  def launch: Boolean = {
    wallStartTime = wallClockStartTime(true)
    if (wallStartTime.isDefined) {
      scheduleStart(wallStartTime)
      true

    } else {
      startActors
      status == Running
    }
  }

  def createActorSystem(name: String, conf: Config): ActorSystem = ActorSystem(name, config)

  def getLoggingAdapter(sys: ActorSystem): LoggingAdapter = sys.log

  def createBus(sys: ActorSystem): Bus = new Bus(sys)

  def getMasterClass: Class[_ <: Actor] = classOf[MasterActor]

  def createSimClock: SettableClock = {
    val startTime = config.getDateTimeOrElse("start-time", DateTime.now) // in sim time
    val timeScale = config.getDoubleOrElse("time-scale", 1.0)
    val endTime = config.getOptionalDateTime("end-time").orElse { // both in sim time
      config.getOptionalFiniteDuration("run-for") match {
        case Some(d) => startTime + d
        case None => DateTime.UndefinedDateTime
      }
    }
    info(s"start-time: $startTime")
    info(s"time-scale:  $timeScale")
    if (endTime.isDefined) info(s"end-time:   $endTime")
    new SettableClock(startTime, timeScale, endTime, stopped = true)
  }

  def timeScale: Double = simClock.timeScale

  def wallClockStartTime (isLaunched: Boolean): DateTime = {
    config.getOptionalDateTime("start-at").orElse {
      config.getOptionalFiniteDuration("start-in") match {
        case Some(d) => DateTime.now + d
        case None => DateTime.UndefinedDateTime
      }
    }
  }

  def scheduleStart(date: DateTime) = {
    info(s"scheduling start of universe $name at $date")
    val dur = FiniteDuration(date.toEpochMillis - System.currentTimeMillis(), MILLISECONDS)
    system.scheduler.scheduleOnce(dur, new Runnable {
      override def run: Unit = {
        startActors
      }
    })
  }
  def scheduleTermination(date: DateTime) = {
    info(s"scheduling termination of universe $name at $date")
    val dur = FiniteDuration(date.toEpochMillis - System.currentTimeMillis(), MILLISECONDS)
    system.scheduler.scheduleOnce(dur, new Runnable {
      override def run: Unit = terminateActors
    })
  }

  protected[this] def createRaceContext(master: ActorRef, bus: Bus): RaceContext = RaceContext(master, bus)

  def getActorConfigs = config.getOptionalConfigList("actors")

  /**
    * this searches for named (toplevel) actors in this system
    *
    * note this actor already has to be instantiated when calling this function hence we don't use normal (and
    * potentially longer) actor timeouts
    */
  def getOptionalRaceActorRef (actorName: String): Option[ActorRef] = {
    val sel = system.actorSelection(s"/user/$name/$actorName")
    val future: Future[ActorRef] = sel.resolveOne(1.second)
    try {
      Some(Await.result(future,1.second))
    } catch {
      case _: ActorNotFound  => None
      case _: TimeoutException => None
    }
  }

  /**
    * mandatory RaceActor lookup
    */
  def getRaceActorRef (actorName: String): ActorRef = {
    val sel = system.actorSelection(s"/user/$name/$actorName")
    val future: Future[ActorRef] = sel.resolveOne(1.second)
    Await.result(future,1.second) // this throws a ActorNotFound or TimedoutException if it does not succeed
  }

  //--- the state transition methods

  def createActors = {
    info(s"creating actors of universe $name ..")
    askVerifiableForResult(master, RaceCreate) {
      case RaceCreated =>
        status = Created
        info(s"universe $name created")
      case RaceCreateFailed(reason) => error(s"creating universe $name failed with: $reason")
      case TimedOut => error(s"creating universe $name timed out")
      case e => error(s"invalid response creating universe $name: $e")
    }(createTimeout)
  }

  def initializeActors: Boolean = {
    info(s"initializing actors of universe $name ..")
    askVerifiableForResult(master, RaceInitialize) {
      case RaceInitialized(commonCaps) =>
        status = Initialized
        localCapabilities = commonCaps
        info(s"universe $name initialized")
        true
      case RaceInitializeFailed(reason) =>
        abort(s"initializing universe $name failed with: $reason")
        false
      case TimedOut =>
        abort(s"initializing universe $name timed out")
        false
      case e =>
        abort(s"invalid response initializing universe $name: $e")
        false
    }(initTimeout)
  }

  def abort (msg: String): Unit = {
    error(msg)
    terminateActors
  }

  // called by RACE driver (TODO enforce or verify)
  def startActors: Boolean = {
    if (status == Initialized) {
      if (simClock.wallEndTime.isDefined) scheduleTermination(simClock.wallEndTime)

      info(s"starting actors of universe $name ..")
      status = Started
      askVerifiableForResult(master, RaceStart) {
        case RaceStarted() =>
          status = Running
          info(s"universe $name running")
          true
        case RaceStartFailed(reason) =>
          abort(s"starting universe $name failed: $reason")
          false
        case TimedOut =>
          abort(s"starting universe $name timed out")
          false
        case e =>
          abort(s"invalid response starting universe $name: $e")
          false
      }(startTimeout)
    } else {
      warning(s"universe $name cannot be started in state $status")
      false
    }
  }

  /**
    * graceful shutdown that synchronously processes terminateRaceActor() actions
    * called by RACE driver (TODO enforce or verify)
    * NOTE - this can't be called from a receive method of an actor since it would hang
    */
  def terminateActors: Boolean = {
    if (isLive) {
      info(s"universe $name terminating..")
      status = Terminating

      askVerifiableForResult(master, RaceTerminate) {
        case RaceTerminated() =>
          raceTerminated
          true
        case RaceTerminateFailed =>
          warning(s"$name termination failed")
          false
        case TimedOut =>
          warning(s"universe $name termination timeout")
          false
      }(terminateTimeout)
    } else { // nothing to shut down
      true
    }
  }

  def isTerminating = status == Terminating
  def isPaused = status == Paused
  def isRunning = isLive && status != Paused
  def isLive = status != Terminating && status != gov.nasa.race.common.Status.Terminated

  def currentStatus = status

  // internal (overridable) method to clean up *after* successful termination of all actors
  protected def raceTerminated: Unit = {
    info(s"universe $name terminated")
    status = common.Status.Terminated
    RaceLogger.terminate(system)

    if (!isRunningEmbedded) {
      system.terminate()  // don't terminate if running in a MultiJvm test
    }
  }

  //--- actor requests affecting the whole RAS

  protected[core] def setPaused: Unit = {
    status = Paused
    simClock.stop
  }

  protected[core] def setResumed: Unit = {
    status = Running
    simClock.resume
  }

  /**
    * to be used from a non-actor context
    */
  def pauseActors: Boolean = {
    if (localCapabilities.supportsPauseResume) {
      if (status == Running) {
        info(s"universe $name pausing..")

        askVerifiableForResult(master, RacePause) {
          case RacePaused =>
            info(s"universe $name paused at ${simClock.dateTime}")
            true
          case RacePauseFailed(reason) =>
            warning(s"universe $name pause failed: $reason")
            false
          case TimedOut =>
            warning(s"universe $name pause timeout")
            false
          case other =>
            warning(s"universe $name got unexpected RacePause reply: $other")
            false
        }
      } else {
        warning(s"universe $name not running, ignore pause")
        false
      }
    } else {
      warning("ignoring pause request: universe does not support pause/resume")
      false
    }
  }

  /**
    * to be used from a non-actor context
    */
  def resumeActors: Boolean = {
    if (localCapabilities.supportsPauseResume) {
      if (status == Paused) {
        info(s"universe $name resuming..")

        askVerifiableForResult(master, RaceResume) {
          case RaceResumed =>
            info(s"universe $name resumed at ${simClock.dateTime}")
            true
          case RaceResumeFailed(reason) =>
            warning(s"universe $name resume failed: $reason")
            false
          case TimedOut =>
            warning(s"universe $name resume timeout")
            false
          case other =>
            warning(s"universe $name got unexpected RaceResume reply: $other")
            false
        }
      } else {
        warning(s"universe $name not paused, ignore resume")
        false
      }
    } else {
      warning("ignoring resume request: universe does not support pause/resume")
      false
    }
  }

  def requestTermination(actorRef: ActorRef) = {
    if (!isTerminating) { // avoid recursive termination
      if ((allowSelfTermination) || (allowRemoteTermination && isRemoteActor(actorRef))) {
        // make sure we don't hang if this is called from a receive method
        ThreadUtils.execAsync(terminateActors)
      }
      else warning(s"universe ignoring termination request from ${actorRef.path}")
    }
  }

  def supportsSimClockReset: Boolean = {
    localCapabilities.supportsSimTimeReset && remoteCapabilities.supportsSimTimeReset
  }

  /**
    * some actor asks for a simClock reset
    */
  def requestSimClockReset(requester: ActorRef, date: DateTime, tScale: Double): Boolean = {
    if (isLive) {
      // clock reset needs to be supported locally and remotely
      if (supportsSimClockReset) {
        info(s"sim clock reset on behalf of ${requester.path.name} to ($date,$tScale)")
        simClock.reset(date, tScale)
        master ! RaceResetClock(requester,date,tScale)
        usedRemoteMasters.values.foreach( remoteMaster=> remoteMaster ! RemoteClockReset(date,tScale))
        true
      } else {
        warning(s"universe $name rejected sim clock reset (insufficient actor capabilities)")
        false
      }
    } else false // nothing to reset
  }

  def setTimescale (tScale: Double): Boolean = {
    if (isLive) {
      if (localCapabilities.supportsSimTimeReset) {
        info(s"set sim clock timescale to: $tScale")
        val date = simClock.dateTime
        simClock.reset(date,tScale)
        master ! RaceResetClock(ActorRef.noSender, date, tScale)
        true
      } else {
        warning("setting time scale ignored (insufficient actor capabilities)")
        false
      }
    } else {
      warning(s"universe $name is not live")
      false
    }
  }

  def pauseResume: Boolean = {
    if (isLive) {
      if (localCapabilities.supportsPauseResume) {
        if (simClock.isStopped) {
          info("resuming sim clock")
          simClock.resume
        } else {
          info("stopping sim clock")
          simClock.stop
        }
        master ! RaceResetClock(ActorRef.noSender, simClock.dateTime, simClock.timeScale)
        true
      } else {
        warning("pause/resume ignored (insufficient actor capabilities)")
        false
      }
    } else {
      warning(s"universe $name is not live")
      false
    }
  }

  def isStopped = simClock.isStopped

  /**
    * check if we accept connection requests from this remote master, i.e. some of our local actors are used
    * as its remote actors. In case the request is accepted, store the master in `usingRemoteMasters`
    *
    * @return true if request is accepted
    */
  def acceptsRemoteConnectionRequest (remoteMaster: ActorRef): Boolean = {
    // TODO check against config if we accept a connection request from this remote master
    // this can filter based on network (ip address) and universe name
    usingRemoteMasters = usingRemoteMasters + remoteMaster
    true
  }

  def isKnownRemoteMaster (actorRef: ActorRef) = usingRemoteMasters.contains(actorRef)

  final val systemPrefix = s"akka://$name" // <2do> check managed remote actor paths
  def isRemoteActor(actorRef: ActorRef) = !actorRef.path.toString.startsWith(systemPrefix)

  def addUsedRemote (remoteUri: String, remoteMaster: ActorRef, caps: RaceActorCapabilities): Unit = {
    usedRemoteMasters = usedRemoteMasters + (remoteUri -> remoteMaster)
    remoteCapabilities = remoteCapabilities.intersection(caps)
  }

  /**
   * hard shutdown command issued from outside the RaceActorSystem
   * NOTE - this might loose data since actors are not processing their terminateRaceActor()
   */
  def kill = {
    info(s"universe $name killed")
    status = common.Status.Terminated // there is no coming back from here
    RaceLogger.terminate(system)
    system.terminate()
  }

  override def logLevel: LogLevel = RaceLogger.getConfigLogLevel(system,
    config.getOptionalString("loglevel")).getOrElse(system.eventStream.logLevel)

  override def setLogLevel(logLevel: LogLevel) = {
    system.eventStream.setLogLevel(logLevel)
    master ! SetLogLevel(logLevel)
  }

  //--- message injection

  def publish(channel: String, msg: Any) = bus.publish(BusEvent(channel, msg, master))

  def send(actorName: String, msg: Any) = {
    system.actorSelection(s"/user/$name/$actorName") ! msg
  }

  //--- state query & display

  def showActors() = {
    master ! RaceShow
  }

  def showChannels() = {
    println(bus.showChannelSubscriptions)
  }

  // TODO - we should probably support logging to a file here
  def reportException (t: Throwable) = {
    if (showExceptions) {
      t match {
        case x: InvocationTargetException =>
          val cause = x.getCause
          if (cause != null) cause.printStackTrace else x.printStackTrace

        case _ => t.printStackTrace
      }
    }
  }

  //--- failure handling

  /*
   * only control the child actor that caused the ruckus
   * we can't safely re-start child RaceActors since this would require a proper Initialize/Start sequence or
   * otherwise the original message 'receive' is restored and we get all sort of pre-init errors so we keep it
   * consistent between Master and ParentRaceActors
   */
  def defaultSupervisorStrategy: SupervisorStrategy = {
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {

        // if we encounter an initialization problem we disable this actor
        // TODO - maybe we should just shut down here
      case aix: ActorInitializationException =>
        reportException(aix)
        error(aix.getMessage)
        Stop

        // all other exceptions we just report but keep the actor state, trying to go on as long as possible
      case x: Throwable =>
        reportException(x)
        error(x.getMessage)
        Resume

      //case _: ArithmeticException      => Resume
      //case _: NullPointerException     => Restart
      //case _: IllegalArgumentException => Stop
      //case _: Exception                => Escalate
    }
  }
}
