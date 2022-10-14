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


/** a message type that is processed by RaceActor.handleSystemMessage */
trait RaceSystemMessage

trait RemoteRaceSystemMessage extends RaceSystemMessage


//--- remote RAS control messages
// these are only exchanged between masters during remote actor creation
// note that we can't use singleton objects here since we need
case class RemoteConnectionRequest (requestingMaster: ActorRef) extends RemoteRaceSystemMessage
case class RemoteConnectionAccept (capabilities: RaceActorCapabilities) extends RemoteRaceSystemMessage
case class RemoteConnectionReject () extends RemoteRaceSystemMessage
case class RemoteRaceTerminate (remoteMaster: ActorRef)  extends RemoteRaceSystemMessage // Master -> RemoteMaster

//--- messages to support remote bus subscribers/publishers, remoteActor -> BusConnector
case class RemoteSubscribe (actorRef: ActorRef, channel: Channel) extends RemoteRaceSystemMessage
case class RemoteUnsubscribe (actorRef: ActorRef, channel: Channel) extends RemoteRaceSystemMessage

//--- remote clock changes
case class RemoteClockReset (date: DateTime,timeScale: Double) extends RemoteRaceSystemMessage


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
case class RaceActorStarted () extends RaceSystemMessage
case class RaceActorStartReject () extends RaceSystemMessage
case class RaceActorStartFailed (reason: String="unknown") extends RaceSystemMessage

/** pause/resume of RaceActors */
case class PauseRaceActor (originator: ActorRef) extends RaceSystemMessage
case class RaceActorPaused () extends RaceSystemMessage
case class RaceActorPauseFailed (reason: String="unknown") extends RaceSystemMessage

case class ResumeRaceActor (originator: ActorRef) extends RaceSystemMessage
case class RaceActorResumed () extends RaceSystemMessage
case class RaceActorResumeFailed (reason: String="unknown") extends RaceSystemMessage

// sent to parent after actor got stopped
case class RaceActorStopped() extends RaceSystemMessage

// obtain tree of RaceActors (once, or on demand)
case class RegisterRaceActor (registrar: ActorRef, parentQueryPath: String) extends RaceSystemMessage // parent -> registree
case class RaceActorRegistered (ownQueryPath: String) extends RaceSystemMessage // registree -> registrar

// liveness and QoS monitoring
case class PingRaceActor (heartBeat: Long, tPing: Long) extends RaceSystemMessage // parent/registrar -> RA, tPing is requester nanos (relative time)
case class RaceActorPong (heartBeat: Long, tPing: Long, msgCount: Long) extends RaceSystemMessage // registree -> registrar, adding msgCount as progress indicator


/** discrete time mode (note this does support actor local time) */
case object PollStepTime extends RaceSystemMessage  // master -> actors
case class NextStepTime (tSendMillis: Long) extends RaceSystemMessage // active actors -> master (use primitive to simplify serialization)
case object NoStepTime extends RaceSystemMessage // passive actors -> master
case class SetStepTime (tSendMillis: Long) extends RaceSystemMessage // master -> selected actor

/** sim clock change notifications */
case class SyncWithRaceClock() extends RaceSystemMessage // master -> actors

/** inform RaceActor of termination */
case class TerminateRaceActor (originator: ActorRef) extends RaceSystemMessage
case class RaceActorTerminated () extends RaceSystemMessage
case class RaceActorTerminateReject () extends RaceSystemMessage
case class RaceActorTerminateFailed (reason: String="unknown") extends RaceSystemMessage

case object RaceTerminateRequest  // ras internal termination request: RaceActor -> Master

case class RaceAck () extends RaceSystemMessage // generic acknowledgement, sent directly
case object RaceTick extends RaceSystemMessage // used to trigger periodic actions

case class RaceRetry(e:Any) // to-reprocess a message
case class RaceExecRunnable (f: Runnable) extends RaceSystemMessage // to execute a runnable within the actor thread

//--- RaceActorSystem control messages (RaceActorSystem <-> Master <-> remoteMaster)
protected[core] case object RaceCreate                // RAS -> Master
protected[core] case object RaceCreated               // Master -> RAS
protected[core] case class  RaceCreateFailed (reason: Any) // Master -> RAS

protected[core] case object RaceInitialize            // RAS -> Master
protected[core] case class RaceInitialized (commonCapabilities: RaceActorCapabilities) // Master -> RAS
protected[core] case class RaceInitializeFailed (reason: Any)  // Master -> RAS

protected[core] case object RaceStart                 // RAS -> Master
protected[core] case class RemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double) // Master -> remote Master
protected[core] case class RaceStarted ()              // Master -> RAS, remote Master -> Master
protected[core] case class RaceStartFailed (reason: Any) // Master -> RAS

protected[core] case object RacePause                  // RAS -> Master
case class RacePauseRequest()                          // toplevel RA -> Master
case class RacePaused()                                // master -> requesting RA
case class RacePauseFailed (reason: Any)

protected[core] case object RaceResume                // RAS -> Master
case class RaceResumeRequest()                        // toplevel RA -> Master
case class RaceResumed()                              // Master -> requesting RA
case class RaceResumeFailed (reason: Any)

protected[core] case object RaceShow                  // RAS -> Master

protected[core] case class RaceTerminate ()           // RAS -> Master
protected[core] case class RaceTerminated ()          // Master -> RAS, remote Master -> Master
protected[core] case class RaceTerminateFailed ()     // Master -> RAS

case class RaceResetClock (originator: ActorRef,d: DateTime,tScale: Double) // originator -> Master
case class RaceClockReset()            // Master -> originator
case class RaceClockResetFailed()      // Master -> originator

case class SetLogLevel (newLevel: LogLevel) extends RaceSystemMessage

// time keeping between actor systems
trait ClockMessage extends RaceSystemMessage
case class SyncSimClock (date: DateTime, timeScale: Double) extends ClockMessage
case class StopSimClock() extends ClockMessage
case class ResumeSimClock() extends ClockMessage

// dynamic subscriptions
case class Subscribe (channel: String)
case class Unsubscribe (channel: String)
case class Publish (channel: String, msg: Any)


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

/**
 * wrapper for delayed action execution
 * Note this is not a system message, i.e. if there is no handler it will be ignored
 */
case class DelayedAction(originator: ActorRef, action: ()=>Unit)


//--- user level ack request/response

/**
  * this is sent as a BusEvent payload. The originator can tag the request to map replies to requests
  * if a responderType is provided only respectively typed actors are supposed to reply
  */
case class SyncRequest(originator: ActorRef, tag: Any, responderType: Option[Class[_]] = None)

/**
  * this is sent directly from the responder to the originator, adding the responder type
  */
case class SyncResponse(responder: ActorRef, responderClass: Class[_], request: SyncRequest)


//--- actor log request

sealed trait RaceLogMsg extends RaceSystemMessage {
  def msg: String
}
case class RaceLogInfo (msg: String) extends RaceLogMsg
case class RaceLogWarning (msg: String) extends RaceLogMsg
case class RaceLogError (msg: String) extends RaceLogMsg

