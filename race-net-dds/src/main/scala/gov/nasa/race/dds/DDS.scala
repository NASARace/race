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

package gov.nasa.race.dds

import com.typesafe.config.Config
import org.omg.dds.core.{ServiceEnvironment, WaitSet}
import org.omg.dds.domain.DomainParticipantFactory
import org.omg.dds.pub.DataWriter
import org.omg.dds.sub.{DataReader, SampleState}
import org.omg.dds.topic.Topic

import scala.reflect._

/**
  * per-process DDS singletons - this is where the global DDS init goes
  */
object DDS {
  val serviceEnvironment = ServiceEnvironment.createInstance(getClass.getClassLoader)
  val participantFactory = DomainParticipantFactory.getInstance(serviceEnvironment)
  val participant = participantFactory.createParticipant

  // those might go into the readers/writers if publisher/subscriber can be configurable or need to be writer/reader specific
  lazy val publisher = participant.createPublisher
  lazy val subscriber = participant.createSubscriber
}

/**
  * common reader/writer features
  *
  * Note that all DDS object creation is encapsulated in methods that can be overridden by subclasses, to
  * support more specialized DDS QoS settings
  */
abstract class DDSClient[T:ClassTag](conf: Config) {
  val topic: Topic[T] = createTopic[T]

  // only convenience methods so far, to simplify DDSWriter/Reader implementations
  def serviceEnvironment = DDS.serviceEnvironment
  def participant = DDS.participant
  def publisher = DDS.publisher
  def subscriber = DDS.subscriber

  protected def createTopic[T:ClassTag]: Topic[T] = {
    participant.createTopic(conf.getString("topic"), classTag[T].runtimeClass).asInstanceOf[Topic[T]]
  }
}

/**
  * concrete DDS Topic[T]/DataWriter[T] combinations
  *
  * The idea is to have any DDS construct that has a type parameter (and hence cannot be instantiated generically)
  * encapsulated within DDSWriter instances
  */
abstract class DDSWriter[T: ClassTag] (conf: Config) extends DDSClient[T](conf) {
  val writer: DataWriter[T] = createWriter

  protected def createWriter: DataWriter[T] = publisher.createDataWriter(topic)

  def write (o: Any): Unit // implemented by subclass, calling writer.write(t)

  def close: Unit = {
    writer.close
    // TODO - and probably more to clean up
  }
}

/**
  * same for concrete DDS Topic[T]/DataReader[T] combinations
  */
abstract class DDSReader[T: ClassTag] (conf: Config) extends DDSClient[T](conf) {
  val reader: DataReader[T] = createReader
  val waitSet: WaitSet = createWaitSet

  protected def createReader: DataReader[T] = subscriber.createDataReader(topic)

  protected def createWaitSet: WaitSet = {
    val waitSet = WaitSet.newWaitSet(serviceEnvironment)
    val dataState = subscriber.createDataState
    dataState.`with`(SampleState.NOT_READ)
    val cond = reader.createReadCondition(dataState)
    waitSet.attachCondition(cond)
    waitSet
  }

  def read (f: Any=>Unit) = {
    waitSet.waitForConditions
    val samples = reader.take
    while (samples.hasNext) { f(samples.next)} // avoid converter objects
    samples.close
  }

  def close: Unit = {
    reader.closeContainedEntities
    // TODO - and probably more to clean up
  }
}
