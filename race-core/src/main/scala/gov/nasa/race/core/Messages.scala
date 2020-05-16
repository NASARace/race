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

import akka.actor.ActorRef
import akka.event.Logging.LogLevel
import com.typesafe.config.Config
import gov.nasa.race.uom.DateTime

import scala.concurrent.duration.FiniteDuration

/**
 * RACE specific messages
 */
object Messages {

  /** a message type that is processed by RaceActor.handleSystemMessage */
  trait RaceSystemMessage

  /** a message type that can be published on a Bus */
  trait ChannelMessage {
    val channel: String
    val sender: ActorRef
  }

  /** a user ChannelMessage */
  case class BusEvent (channel: String, msg: Any, sender: ActorRef) extends ChannelMessage

  /** a system ChannelMessage */
  case class BusSysEvent (channel: String, msg: Any, sender: ActorRef) extends ChannelMessage with RaceSystemMessage

  //--- RaceActor system messages (processed by core RaceActor traits)
  // each of the system messages has an associated callback function of the same (lowercase)
  // name and arguments, e.g. initializeRaceActor(raceContext,actorConf). If concrete
  // RaceActors override them (and don't want to deliberately override system processing)
  // they have to call the respective super methods (e.g. super.initializeRaceActor)

  /** set RaceContext and do runtime initialization of RaceActors */
  case class InitializeRaceActor (raceContext: RaceContext, actorConfig: Config) extends RaceSystemMessage
  case class RaceActorInitialized (caps: RaceActorCapabilities) extends RaceSystemMessage
  case class RaceActorInitializeFailed (reason: String="unknown") extends RaceSystemMessage

  /** inform RaceActor of simulation start */
  case class StartRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorStarted extends RaceSystemMessage
  case class RaceActorStartFailed (reason: String="unknown") extends RaceSystemMessage

  /** pause/resume of RaceActors */
  case class PauseRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorPaused extends RaceSystemMessage
  case class RaceActorPauseFailed (reason: String="unknown") extends RaceSystemMessage

  case class ResumeRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorResumed extends RaceSystemMessage
  case class RaceActorResumeFailed (reason: String="unknown") extends RaceSystemMessage

  /** liveness check */
  case object ProcessRaceActor extends RaceSystemMessage
  case object RaceActorProcessed extends RaceSystemMessage

  // sent to parent after actor got stopped
  case object RaceActorStopped extends RaceSystemMessage

  // note we need to separate ping and response to support remote actors, as opposed to using a
  // ephemeral message that is completed by the child actor.
  // The statsCollector is optional. If it is set the ping receiver should send the response to both
  // the sender and the collector. Note that we don't need to pass the originator to the collector
  // since the actor hierarchy is visible from the sender actorref path.
  case class PingRaceActor (sentNanos: Long, statsCollector: ActorRef = ActorRef.noSender) extends RaceSystemMessage
  case class PingRaceActorResponse (receivedNanos: Long, latencyNanos: Long, msgCount: Long)

  // the sync on demand version that lets actors report themselves. Mostly for debugging
  case class ShowRaceActor (sentNanos: Long)
  case object ShowRaceActorResponse

  /** discrete time mode (note this does support actor local time) */
  case object PollStepTime extends RaceSystemMessage  // master -> actors
  case class NextStepTime (tSendMillis: Long) extends RaceSystemMessage // active actors -> master (use primitive to simplify serialization)
  case object NoStepTime extends RaceSystemMessage // passive actors -> master
  case class SetStepTime (tSendMillis: Long) extends RaceSystemMessage // master -> selected actor

  /** sim clock change notifications */
  case object SyncWithRaceClock extends RaceSystemMessage // master -> actors

  /** inform RaceActor of termination */
  case class TerminateRaceActor (originator: ActorRef) extends RaceSystemMessage
  case object RaceActorTerminated extends RaceSystemMessage
  case object RaceActorTerminateIgnored extends RaceSystemMessage
  case class RaceActorTerminateFailed (reason: String="unknown") extends RaceSystemMessage

  case object RaceTerminateRequest  // ras internal termination request: RaceActor -> Master

  case object RaceAck extends RaceSystemMessage // generic acknowledgement
  case object RaceTick extends RaceSystemMessage // used to trigger periodic actions
  case class RaceRetry(e:Any) // to-reprocess a message

  //--- RaceActorSystem control messages (RaceActorSystem <-> Master <-> remoteMaster)
  protected[core] case object RaceCreate                // RAS -> Master
  protected[core] case object RaceCreated               // Master -> RAS
  protected[core] case class  RaceCreateFailed (reason: Any) // Master -> RAS

  protected[core] case object RaceInitialize            // RAS -> Master
  protected[core] case class RaceInitialized (commonCapabilities: RaceActorCapabilities) // Master -> RAS
  protected[core] case class RaceInitializeFailed (reason: Any)  // Master -> RAS

  protected[core] case object RaceStart                 // RAS -> Master
  protected[core] case class RemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) // Master -> remote Master
  protected[core] case object RaceStarted               // Master -> RAS, remote Master -> Master
  protected[core] case class RaceStartFailed (reason: Any) // Master -> RAS

  protected[core] case object RacePause                 // RAS -> Master
  case object RacePauseRequest                          // toplevel RA -> Master
  case object RacePaused
  case class RacePauseFailed (reason: Any)

  protected[core] case object RaceResume                // RAS -> Master
  case object RaceResumeRequest                         // toplevel RA -> Master
  case object RaceResumed
  case class RaceResumeFailed (reason: Any)

  case object RaceShow                                  // RAS -> Master (on demand)
  case object RaceShowCompleted                         // Master -> RAS

  protected[core] case object RaceTerminate             // RAS -> Master
  protected[core] case class RemoteRaceTerminate (remoteMaster: ActorRef) // Master -> RemoteMaster
  protected[core] case object RaceTerminated            // Master -> RAS, remote Master -> Master
  protected[core] case object RaceTerminateFailed       // Master -> RAS

  case class RaceResetClock (originator: ActorRef,d: DateTime,tScale: Double) // originator -> Master
  case object RaceClockReset            // Master -> originator
  case object RaceClockResetFailed      // Master -> originator

  case class SetLogLevel (newLevel: LogLevel) extends RaceSystemMessage

  // time keeping between actor systems
  trait ClockMessage extends RaceSystemMessage
  case class SyncSimClock (date: DateTime, timeScale: Double) extends ClockMessage
  case object StopSimClock extends ClockMessage
  case object ResumeSimClock extends ClockMessage

  // dynamic subscriptions
  case class Subscribe (channel: String)
  case class Unsubscribe (channel: String)
  case class Publish (channel: String, msg: Any)

  //--- messages to support remote bus subscribers/publishers, processed by BusConnector
  case class RemoteSubscribe (actorRef: ActorRef, channel: Channel) extends RaceSystemMessage // -> master
  case class RemoteUnsubscribe (actorRef: ActorRef, channel: Channel)  extends RaceSystemMessage // -> master
  case class RemotePublish (msg: ChannelMessage) extends RaceSystemMessage


  //--- dynamic channel provider lookup & response
  trait ChannelTopicMessage extends RaceSystemMessage

  // note it is still the responsibility of the client to subscribe
  /**
    *  look up channel providers:  c->{p}
    *  sent through system channels and hence have to be wrapped into BusSysEvents
    */
  case class ChannelTopicRequest (channelTopic: ChannelTopic, requester: ActorRef)  extends ChannelTopicMessage {
    def toAccept = ChannelTopicAccept(channelTopic,requester)
    def toResponse(provider: ActorRef) = ChannelTopicResponse(channelTopic, provider)
  }

  /**
    *  response from potential provider: {p}->c
    *  sent point-to-point
    */
  case class ChannelTopicResponse (channelTopic: ChannelTopic, provider: ActorRef)  extends ChannelTopicMessage {
    def toAccept(client: ActorRef) = ChannelTopicAccept(channelTopic,client)
    def toRelease(client: ActorRef) = ChannelTopicRelease(channelTopic,client)
  }

  /**
    * client accepts (registers with one) provider: c->p
    * sent point-to-point
    */
  case class ChannelTopicAccept (channelTopic: ChannelTopic, client: ActorRef)  extends ChannelTopicMessage {
    def toRelease = ChannelTopicRelease(channelTopic,client)
  }

  /**
    * client releases registered provider
    * send point-to-point
    */
  case class ChannelTopicRelease (channelTopic: ChannelTopic, client: ActorRef)  extends ChannelTopicMessage

  // <2do> we also need a message to indicate that a provider with live subscribers is terminated


  case class SetTimeout (msg: Any, duration: FiniteDuration) extends RaceSystemMessage

  case class DelayedAction(originator: ActorRef, action: ()=>Unit)
}
