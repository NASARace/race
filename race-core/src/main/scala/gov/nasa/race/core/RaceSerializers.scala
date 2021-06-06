/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import akka.actor.ExtendedActorSystem
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import gov.nasa.race.uom.DateTime

object RaceSerializers {
  val configRenderOpt = ConfigRenderOptions.concise()

  def serializeConfig (conf: Config): String = conf.root().render(configRenderOpt)
}
import RaceSerializers._

//--- a collection of single-type serializers for RACE system messages

// RemoteConnectionRequest (requestingMaster: ActorRef)
class RemoteConnectionRequestSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RemoteConnectionRequest](system) {
  override def serialize(t: RemoteConnectionRequest): Unit = writeActorRef(t.requestingMaster)
  override def deserialize(): RemoteConnectionRequest = RemoteConnectionRequest(readActorRef())
}

// RemoteConnectionAccept ()
class RemoteConnectionAcceptSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RemoteConnectionAccept](system) {
  override def serialize(t: RemoteConnectionAccept): Unit = {}
  override def deserialize(): RemoteConnectionAccept = RemoteConnectionAccept()
}

// RemoteConnectionReject ()
class RemoteConnectionRejectSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RemoteConnectionReject](system) {
  override def serialize(t: RemoteConnectionReject): Unit = {}
  override def deserialize(): RemoteConnectionReject = RemoteConnectionReject()
}

// RemoteRaceStart (remoteMaster: ActorRef, simTime: DateTime, timeScale: Double)
class RemoteRaceStartSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RemoteRaceStart](system) {
  override def serialize(t: RemoteRaceStart): Unit = {
    writeActorRef(t.remoteMaster)
    writeLong(t.simTime.toEpochMillis)
    writeDouble(t.timeScale)
  }
  override def deserialize(): RemoteRaceStart = {
    val remoteMaster = readActorRef()
    val simTime = DateTime.ofEpochMillis(readLong())
    val timeScale = readDouble()
    RemoteRaceStart(remoteMaster,simTime,timeScale)
  }
}

// RaceStarted ()
class RaceStartedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceStarted](system) {
  override def serialize(t: RaceStarted): Unit = {}
  override def deserialize(): RaceStarted = RaceStarted()
}

// class RemoteRaceTerminate (remoteMaster: ActorRef)
class RemoteRaceTerminateSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RemoteRaceTerminate](system) {
  override def serialize(t: RemoteRaceTerminate): Unit = writeActorRef(t.remoteMaster)
  override def deserialize(): RemoteRaceTerminate = RemoteRaceTerminate(readActorRef())
}

// RaceTerminated ()
class RaceTerminatedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceTerminated](system) {
  override def serialize(t: RaceTerminated): Unit = {}
  override def deserialize(): RaceTerminated = RaceTerminated()
}

//--- InitializeRaceActor

// InitializeRaceActor (raceContext: RaceContext, actorConfig: Config)
//   RaceContext (masterRef: ActorRef, bus: BusInterface)
//   RemoteBusInterface (masterRef: ActorRef, connectorRef: ActorRef)
class InitializeRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[InitializeRaceActor](system) {
  override def serialize(t: InitializeRaceActor): Unit = {
    val remoteBusIfc = t.raceContext.bus.asInstanceOf[RemoteBusInterface]

    writeActorRef(remoteBusIfc.masterRef) // the masterActor of the controlling RAS
    writeActorRef(remoteBusIfc.connectorRef) // the connector to be used by the (remote) initializee
    writeUTF(serializeConfig(t.actorConfig)) // the config of the controlling RAS for the initializee
  }
  override def deserialize(): InitializeRaceActor = {
    val remoteMasterRef = readActorRef()
    val remoteConnectorRef = readActorRef()
    val actorConfig = ConfigFactory.parseString(readUTF())

    val remoteBusInterface = RemoteBusInterface(remoteMasterRef,remoteConnectorRef)
    InitializeRaceActor( RaceContext(remoteMasterRef,remoteBusInterface), actorConfig)
  }
}

// RaceActorInitialized (caps: RaceActorCapabilities)
//    RaceActorCapabilities (caps: Int)
class RaceActorInitializedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorInitialized](system) {
  override def serialize(t: RaceActorInitialized): Unit = writeInt(t.caps.caps)
  override def deserialize(): RaceActorInitialized = RaceActorInitialized(RaceActorCapabilities(readInt()))
}

// RaceActorInitializeFailed (reason: String)
class RaceActorInitializeFailedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorInitializeFailed](system) {
  override def serialize(t: RaceActorInitializeFailed): Unit = writeUTF(t.reason)
  override def deserialize(): RaceActorInitializeFailed = RaceActorInitializeFailed(readUTF())
}

//--- StartRaceActor

// StartRaceActor (originator: ActorRef)
class StartRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[StartRaceActor](system) {
  override def serialize(t: StartRaceActor): Unit = writeActorRef(t.originator)
  override def deserialize(): StartRaceActor = StartRaceActor(readActorRef())
}

// RaceActorStarted ()
class RaceActorStartedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorStarted](system) {
  override def serialize(t: RaceActorStarted): Unit = {}
  override def deserialize(): RaceActorStarted = RaceActorStarted()
}

// RaceActorStartFailed (reason: String)
class RaceActorStartFailedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorStartFailed](system) {
  override def serialize(t: RaceActorStartFailed): Unit = writeUTF(t.reason)
  override def deserialize(): RaceActorStartFailed = RaceActorStartFailed(readUTF())
}

//--- PauseRaceActor

// RacePauseRequest()
class RacePauseRequestSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RacePauseRequest](system) {
  override def serialize(t: RacePauseRequest): Unit = {}
  override def deserialize(): RacePauseRequest = RacePauseRequest()
}

// RacePaused()
class RacePausedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RacePaused](system) {
  override def serialize(t: RacePaused): Unit = {}
  override def deserialize(): RacePaused = RacePaused()
}

// PauseRaceActor()
class PauseRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[PauseRaceActor](system) {
  override def serialize(t: PauseRaceActor): Unit = writeActorRef(t.originator)
  override def deserialize(): PauseRaceActor = PauseRaceActor(readActorRef())
}

// RaceActorPaused ()
class RaceActorPausedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorPaused](system) {
  override def serialize(t: RaceActorPaused): Unit = {}
  override def deserialize(): RaceActorPaused = RaceActorPaused()
}

// RaceActorPauseFailed (reason: String)
class RaceActorPauseFailedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorPauseFailed](system) {
  override def serialize(t: RaceActorPauseFailed): Unit = writeUTF(t.reason)
  override def deserialize(): RaceActorPauseFailed = RaceActorPauseFailed(readUTF())
}

//--- ResumeRaceActor

// RaceResumeRequest()
class RaceResumeRequestSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceResumeRequest](system) {
  override def serialize(t: RaceResumeRequest): Unit = {}
  override def deserialize(): RaceResumeRequest = RaceResumeRequest()
}

// RaceResumed()
class RaceResumedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceResumed](system) {
  override def serialize(t: RaceResumed): Unit = {}
  override def deserialize(): RaceResumed = RaceResumed()
}

class ResumeRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[ResumeRaceActor](system) {
  override def serialize(t: ResumeRaceActor): Unit = writeActorRef(t.originator)
  override def deserialize(): ResumeRaceActor = ResumeRaceActor(readActorRef())
}

// RaceActorStarted ()
class RaceActorResumedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorResumed](system) {
  override def serialize(t: RaceActorResumed): Unit = {}
  override def deserialize(): RaceActorResumed = RaceActorResumed()
}

// RaceActorStartFailed (reason: String)
class RaceActorResumeFailedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorResumeFailed](system) {
  override def serialize(t: RaceActorResumeFailed): Unit = writeUTF(t.reason)
  override def deserialize(): RaceActorResumeFailed = RaceActorResumeFailed(readUTF())
}

//--- TerminateRaceActor

// TerminateRaceActor (originator: ActorRef)
class TerminateRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[TerminateRaceActor](system) {
  override def serialize(t: TerminateRaceActor): Unit = writeActorRef(t.originator)
  override def deserialize(): TerminateRaceActor = TerminateRaceActor(readActorRef())
}

// RaceActorTerminated ()
class RaceActorTerminatedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorTerminated](system) {
  override def serialize(t: RaceActorTerminated): Unit = {}
  override def deserialize(): RaceActorTerminated = RaceActorTerminated()
}

// RaceActorTerminateReject ()
class RaceActorTerminateRejectSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorTerminateReject](system) {
  override def serialize(t: RaceActorTerminateReject): Unit = {}
  override def deserialize(): RaceActorTerminateReject = RaceActorTerminateReject()
}

// RaceActorTerminateFailed (reason: String)
class RaceActorTerminateFailedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorTerminateFailed](system) {
  override def serialize(t: RaceActorTerminateFailed): Unit = writeUTF(t.reason)
  override def deserialize(): RaceActorTerminateFailed = RaceActorTerminateFailed(readUTF())
}

//--- liveness and QoS

// RegisterRaceActor (registrar: ActorRef, parentQueryPath: String)
class RegisterRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RegisterRaceActor](system) {
  override def serialize(t: RegisterRaceActor): Unit = {
    writeActorRef(t.registrar)
    writeUTF(t.parentQueryPath)
  }
  override def deserialize(): RegisterRaceActor = {
    val registrar = readActorRef()
    val parentQueryPath = readUTF()
    RegisterRaceActor(registrar, parentQueryPath)
  }
}

// RaceActorRegistered (ownQueryPath: String)
class RaceActorRegisteredSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorRegistered](system) {
  override def serialize(t: RaceActorRegistered): Unit = {
    writeUTF(t.ownQueryPath)
  }
  override def deserialize(): RaceActorRegistered = {
    val ownQueryPath = readUTF()
    RaceActorRegistered(ownQueryPath)
  }
}

// PingRaceActor (heartBeat: Long, tPing: Long)
class PingRaceActorSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[PingRaceActor](system) {
  override def serialize(t: PingRaceActor): Unit = {
    writeLong(t.heartBeat)
    writeLong(t.tPing)
  }
  override def deserialize(): PingRaceActor = {
    val heartBeat = readLong()
    val tPing = readLong()
    PingRaceActor(heartBeat, tPing)
  }
}

// RaceActorPong (heartBeat: Long, tPing: Long, msgCount: Long)
class RaceActorPongSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorPong](system) {
  override def serialize(t: RaceActorPong): Unit = {
    writeLong(t.heartBeat)
    writeLong(t.tPing)
    writeLong(t.msgCount)
  }
  override def deserialize(): RaceActorPong = {
    val heartBeat = readLong()
    val tPing = readLong()
    val msgCount = readLong()
    RaceActorPong(heartBeat, tPing, msgCount)
  }
}


// RaceAck ()
class RaceAckSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceAck](system) {
  override def serialize(t: RaceAck): Unit = {}
  override def deserialize(): RaceAck = RaceAck()
}

// RaceActorStopped ()
class RaceActorStoppedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceActorStopped](system) {
  override def serialize(t: RaceActorStopped): Unit = {}
  override def deserialize(): RaceActorStopped = RaceActorStopped()
}

// RaceClockReset()
class RaceClockResetSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceClockReset](system) {
  override def serialize(t: RaceClockReset): Unit = {}
  override def deserialize(): RaceClockReset = RaceClockReset()
}

// RaceClockResetFailed()
class RaceClockResetFailedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[RaceClockResetFailed](system) {
  override def serialize(t: RaceClockResetFailed): Unit = {}
  override def deserialize(): RaceClockResetFailed = RaceClockResetFailed()
}

//---------------------- TODO - and more...