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

package gov.nasa.race.test

import akka.actor._
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import gov.nasa.race.common.Status._
import gov.nasa.race.core.Messages._
import gov.nasa.race.core.{Bus, MasterActor, RaceActor, RaceActorSystem, TimedOut, _}
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfterAll

import scala.collection.immutable.ListMap
import scala.collection.{Map, Seq}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

object TestRaceActorSystem {
  def createTestConfig(name: String): Config = {
    ConfigFactory.empty.withValue("name", ConfigValueFactory.fromAnyRef(name))
  }

  case object ResetMaster
  case object MasterReset
  case class WatchRaceActor(actorRef:ActorRef)

  /**
    * a Bus for actor system tests that is fully functional except of remoting
    */
  class TestBus (system: ActorSystem) extends Bus(system) {
    def reset = channelSubscriptions.clear()
  }

  /**
    * this differs from a normal MasterActor by keeping RaceActors alive
    * and registered until the TestActorSystem is reset, otherwise we would
    * loose the TestKit capability to inspect the actor objects (throws an
    * exception if actorRef is already terminated)
    * This means we (a) have to delay 'ras.actors' update and (b) avoid
    * stop() upon TerminateRaceActor processing
    * Also note that we don't create the tested actors, i.e. we don't supervise,
    * but we do death-watch them
    */
  class TestMaster (ras: RaceActorSystem) extends MasterActor(ras) {
    var waiterForTerminated: Option[ActorRef] = None

    override def receive = {
      testReceive.orElse(super.receive)
    }

    // we don't supervise the test actors, but we still start them
    override def isSupervised (actorRef: ActorRef) = true

    def testReceive: Receive = {
      case WatchRaceActor(actorRef) =>
        context.watch(actorRef) // we are not supervising test actors, but start death watch

      case akka.actor.Terminated(actorRef) =>
        ras.actors = ras.actors - actorRef
        if (ras.actors.isEmpty) waiterForTerminated.map( _ ! MasterReset)

      case ResetMaster =>
        for ((actorRef,_) <- ras.actors) {
          context.stop(actorRef) // will cause Terminated messages to the master
        }

        if (ras.actors.isEmpty) sender ! MasterReset
        else waiterForTerminated = Some(sender)

        remoteContexts.clear()
    }

    override def setUnrespondingTerminatees(unresponding: Seq[(ActorRef,Config)]) = {}
    override def stopRaceActor (actorRef: ActorRef) = {}
  }
}
import gov.nasa.race.test.TestRaceActorSystem._

class TestRaceActorSystem (name: String) extends RaceActorSystem(createTestConfig(name)) {
  override def createBus (sys: ActorSystem) = new TestBus(sys)
  override def getMasterClass = classOf[TestMaster]
  override def raceTerminated = {} // we don't terminate before all tests are done
  override def stoppedRaceActor (actorRef: ActorRef) = {} // leave cleanup for reset

  def addTestActor (actorRef: ActorRef, conf: Config): Unit = {
    actors = actors + (actorRef -> conf)
    master ! WatchRaceActor(actorRef)
  }

  def reset = {
    askForResult(master ? ResetMaster) {
      case MasterReset => // done
      case TimedOut => throw new RuntimeException("master reset timed out")
      case other => throw new RuntimeException(s"invalid master reset response $other")
    }

    actors = ListMap.empty[ActorRef,Config]
    usedRemoteMasters = Map.empty[String,ActorRef]
    bus.asInstanceOf[TestBus].reset

    status = Initializing
  }
}

object RaceActorSpec {
  // expect API messages and return values
  case object Expect
  case object Continue // returned by assertion to indicate we expect more
  case object Success

  class BusProbe(assertion: PartialFunction[Any,Any]) extends Actor {
    var waiterForExpect: Option[ActorRef] = None

    def receive: Receive = {
      case Expect => waiterForExpect = Some(sender)

      case msg =>
        if (assertion.isDefinedAt(msg)){
          assertion(msg) match {
            case Continue => // msg was accepted but go on - we expect more
            case other => // we count that as "expectation met"
              waiterForExpect.map( _ ! Success)
          }
        }
    }
  }
}
import gov.nasa.race.test.RaceActorSpec._

/**
  * a RaceSpec that uses the Akka TestKit for testing RaceActors
  * while we do use a (Test) RaceActorSystem, we do instantiate and register actors
  * explicitly from the concrete RaceActorSpec, i.e. bypass normal RaceActorSystem/Master instantiation
  *
  * <2do> add generic system assertions and test termination
  */
abstract class RaceActorSpec (tras: TestRaceActorSystem) extends TestKit(tras.system) with RaceSpec with BeforeAndAfterAll {

  def this() = this(new TestRaceActorSystem("TestRaceActor"))

  def bus = tras.bus

  def runRaceActorSystem(level: LogLevel=Logging.WarningLevel) (f: => Any) = {
    setLogLevel(level)
    println("\n====================================== begin actor test")
    try {
      f
    } finally {
      reset
      println("====================================== end actor test")
    }
  }

  def reset = tras.reset

  def addTestActor[T <: RaceActor: ClassTag] (actorCls: Class[T], name: String, ctorConf: Config): TestActorRef[T] = {
    val ctorConfig = ctorConf.withValue("name", ConfigValueFactory.fromAnyRef(name))
    addTestActor(actorCls, name, ctorConfig, ctorConfig)
  }

  def addTestActor[T <: RaceActor: ClassTag] (actorCls: Class[T], name: String,
                                              ctorConf: Config, initConfig: Config): TestActorRef[T] = {
    val ctorConfig = ctorConf.withValue("name", ConfigValueFactory.fromAnyRef(name))
    val actorRef = TestActorRef[T](Props(actorCls,ctorConfig),name)
    tras.addTestActor(actorRef,initConfig)
    actorRef
  }

  override def afterAll {
    println("\nshutting down TestKit actor system\n")
    TestKit.shutdownActorSystem(system)
  }

  def actor[T <: RaceActor: ClassTag] (tref: TestActorRef[T]) = tref.underlyingActor

  def setLogLevel (level: LogLevel) = system.eventStream.setLogLevel(level)

  def initializeTestActors = {
    println("------------- initializing test actors")
    tras.initializeActors
  }

  def startTestActors (simTime: DateTime) = {
    println(s"------------- starting test actors at $simTime")
    tras.startActors
  }

  def terminateTestActors = {
    println("------------- terminating test actors")
    tras.terminate
  }

  def printTestActors = {
    println(s"------------- test actors of actor system: $system")
    for (((actorRef,initConf),i) <- tras.actors.zipWithIndex) {
      val actor = actorRef.asInstanceOf[TestActorRef[_]].underlyingActor
      println(f"  ${i+1}%2d: ${actorRef.path} : ${actor.getClass.getName}")
    }
  }

  def printBusSubscriptions = {
    println(s"------------- bus subscriptions for actor system: $system")
    println(tras.showChannels)
  }

  var nExpect = 0
  def expectBusMsg (channel:String, timeout: FiniteDuration, trigger: => Any = {}) (assertion: PartialFunction[Any,Any]) = {
    nExpect += 1
    val probe = system.actorOf(Props(classOf[BusProbe],assertion), s"expectBusMsg-${nExpect}")
    bus.subscribe(probe,channel)
    val future = probe.ask(Expect)(Timeout(timeout))
    trigger // don't trigger before we have subscribed the probe
    Await.result(future, timeout) match { // don't catch the timeout, pass it up
      case Success => println(s"expectBusMsg($channel) succeeded")
    }
    bus.unsubscribe(probe)
    system.stop(probe) // clean it up
  }

  def publish(channel: String, msg: Any, originator: ActorRef=testActor) = {
    bus.publish(BusEvent(channel,msg,originator))
  }

  def processMessages (actorRef: ActorRef, timeout: FiniteDuration) = {
    val future = actorRef.ask(ProcessRaceActor)(Timeout(timeout))
    Await.result(future,timeout) match {
      case RaceActorProcessed =>
    }
  }
}

