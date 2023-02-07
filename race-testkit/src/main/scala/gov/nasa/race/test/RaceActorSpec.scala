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

import java.util.concurrent.LinkedBlockingQueue
import akka.actor._
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import gov.nasa.race.core.{Bus, BusEvent, MasterActor, RaceActor, RaceActorSystem, RaceShow, askForResult}
import gov.nasa.race.uom.DateTime
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.reflect._
import scala.jdk.CollectionConverters._

object TestRaceActorSystem {
  def createTestConfig(name: String): Config = {
    ConfigFactory.empty
      .withValue("name", ConfigValueFactory.fromAnyRef(name))
      .withValue("akka.loggers", ConfigValueFactory.fromIterable(Seq("gov.nasa.race.core.RaceLogger").asJava))
  }

  def createTestConfig(value: (String,String)*): Config = {
    value.foldLeft(ConfigFactory.empty){ (conf, e) =>
      conf.withValue(e._1, ConfigValueFactory.fromAnyRef(e._2))
    }
  }

  case object ResetMaster
  case object MasterReset
  case object MasterResetFailed
  case object PrintTestActors

  // use this if you want actors to be instantiated by the Master
  case class AddTestActor (ctorConf: Config)
  case class TestActorAdded (actor: Actor)
  case class AddTestActorFailed (ex: Throwable)

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
    */
  class TestMaster (ras: RaceActorSystem) extends MasterActor(ras) {

    override def receive = {
      testReceive.orElse(super.receive)
    }

    def testReceive: Receive = {

      case ResetMaster =>
        if (hasChildActors) terminateRaceActors()

        if (noMoreChildren) {
          sender() ! MasterReset
        } else {
          sender() ! MasterResetFailed
        }

      case AddTestActor(actorConfig) =>
        try {
          val actorName = actorConfig.getString("name")
          val clsName = actorConfig.getString("class")
          val actorCls = ras.classLoader.loadClass(clsName)
          val createActor = getActorCtor(actorCls, actorConfig)

          info(s"creating $actorName ..")
          val sync = new LinkedBlockingQueue[Either[Throwable, ActorRef]](1)
          val props = getActorProps(createActor, sync)

          val actorRef = TestActorRef[Actor](props, self, actorName)(context.system)
          addChildActorRef(actorRef, actorConfig)
          context.watch(actorRef)

          sender() ! TestActorAdded(actorRef.underlyingActor)
        } catch {
          case x: Throwable => sender() ! AddTestActorFailed(x)
        }

      case PrintTestActors => printChildren()
    }
  }
}
import gov.nasa.race.test.TestRaceActorSystem._

class TestRaceActorSystem (name: String) extends RaceActorSystem(createTestConfig(name)) {
  RaceActorSystem.runEmbedded

  override def createBus (sys: ActorSystem) = new TestBus(sys)
  override def getMasterClass = classOf[TestMaster]
  override def raceTerminated = {} // we don't terminate before all tests are done

  def printTestActors(): Unit = master ! PrintTestActors
}

object RaceActorSpec {
  // expect API messages and return values
  case object Expect
  case object Continue // returned by assertion to indicate we expect more
  case object Success

  class BusProbe (assertion: PartialFunction[Any,Any]) extends Actor {
    var waiterForExpect: Option[ActorRef] = None

    def receive: Receive = {
      case Expect => waiterForExpect = Some(sender())

      case msg =>
        if (assertion.isDefinedAt(msg)){
          assertion(msg) match {
            case Continue => // msg was accepted but go on - we expect more
            case _ => // we count that as "expectation met"
              context.become(receiveDone, true) // but there might be more to come (if we just stop we might get deadletters)
              waiterForExpect.foreach( _ ! Success)
          }
        }
    }

    def receiveDone: Receive = {
      case _ => // we don't care anymore
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

  implicit val timeout = Timeout(5.seconds)
  def bus = tras.bus

  def ras: TestRaceActorSystem = tras
  def master: ActorRef = tras.master

  // forwards so that we don't have to import separately
  def createTestConfig(name: String): Config = TestRaceActorSystem.createTestConfig(name)
  def createTestConfig(value: (String,String)*): Config = TestRaceActorSystem.createTestConfig(value:_*)

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

  def reset: Unit = {
    askForResult( master ? ResetMaster){
      case MasterReset => // Ok
      case MasterResetFailed => fail("reset test master failed")
    }
  }

  /**
    * synchronously create and return a actor reference which is a proper child
    * and hence is supervised by master
    * Note: we have to resort to down-casting here since we can't match messages with generic types
    */
  def addTestActor[T <:RaceActor: ClassTag](name: String, ctorConf: Config): T = {
    val conf = ctorConf
      .withValue("name", ConfigValueFactory.fromAnyRef(name))
      .withValue("class", ConfigValueFactory.fromAnyRef(classTag[T].toString))

    val actor = askForResult( master ? AddTestActor(conf)) {
      case TestActorAdded(actor) => actor
      case AddTestActorFailed(exception) =>
        exception.printStackTrace
        fail(s"creating test actor failed with $exception")
      case x =>
        fail(s"adding actor ref for $name failed with $x")
    }

    if (classTag[T].runtimeClass eq actor.getClass) actor.asInstanceOf[T]
    else fail(s"wrong actor class, expected: ${classTag[T]}, got ${actor.getClass}")
  }

  def addTestActor[T <:RaceActor: ClassTag](name: String, confOpts: (String,String)*): T = {
    addTestActor(name,createTestConfig(confOpts:_*))
  }

  override def afterAll(): Unit = {
    println("\nshutting down TestKit actor system\n")
    TestKit.shutdownActorSystem(system)
  }

  def setLogLevel (level: LogLevel) = system.eventStream.setLogLevel(level)

  def initializeTestActors = {
    println("------------- initializing test actors")
    if (!tras.initializeActors){
      fail("failed to initialize test actors")
    }
  }

  def startTestActors (simTime: DateTime) = {
    println(s"------------- starting test actors at $simTime")
    if (!tras.startActors){
      fail("failed to start test actors")
    }
  }

  def terminateTestActors = {
    println("------------- terminating test actors")
    if (!tras.terminateActors){
      fail("failed to terminate test actors")
    }
  }

  def printTestActors = {
    println(s"------------- test actors of actor system: $system")
    tras.printTestActors()
  }

  def printBusSubscriptions = {
    println(s"------------- bus subscriptions for actor system: $system")
    tras.showChannels()
  }

  def sleep (millis: Int): Unit = {
    try {
      Thread.sleep(millis)
    } catch {
      case _:InterruptedException => // we don't care
    }
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

  def expectResponse (aRef: ActorRef, msg: Any, timeout: FiniteDuration) (assertion: PartialFunction[Any,Any]) = {
    val future = aRef.ask(msg)(Timeout(timeout))
    assertion.apply(Await.result(future, timeout))
  }

  def expectResponse (future: Future[Any],timeout: FiniteDuration) (assertion: PartialFunction[Any,Any]) = {
    assertion.apply(Await.result(future, timeout))
  }

  def publish(channel: String, msg: Any, originator: ActorRef=testActor) = {
    bus.publish(BusEvent(channel,msg,originator))
  }

}

