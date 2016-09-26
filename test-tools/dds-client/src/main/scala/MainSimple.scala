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

package gov.nasa.race.ddsclient

import dds.{FlightRecord => DDSFlightRecord}
import gov.nasa.race.common.ConsoleIO._
import gov.nasa.race.common.ThreadUtils
import gov.nasa.race.data.dds.FlightRecord
import org.omg.dds.core.{ServiceEnvironment, WaitSet}
import org.omg.dds.domain.DomainParticipantFactory
import org.omg.dds.sub.{DataReader, SampleState}
import org.omg.dds.topic.Topic
import scopt.OptionParser

import scala.collection.JavaConversions._

/**
  * simple test client that reads FlightRecord instances from a DDS topic
  */
object MainSimple extends App {

  case class CliOpts (
    tn: String = "Flight",
    dds: String = "com.prismtech.cafe.core.ServiceEnvironmentImpl"
  )

  def cliParser = {
    new OptionParser[CliOpts]("ddsserver") {
      help("help") abbr ("h") text ("print this help")
      opt[String]("topic") text ("topic name") optional() action {(v, o) => o.copy(tn = v)}
      opt[String]("dds") text ("DDS implementation class") optional() action {(v, o) => o.copy(dds = v)}
    }
  }
  val opts: CliOpts = cliParser.parse(args, CliOpts()).get

  System.setProperty("logback.configurationFile", "logback-ddsclient.xml")
  System.setProperty(ServiceEnvironment.IMPLEMENTATION_CLASS_NAME_PROPERTY,opts.dds)

  val env = ServiceEnvironment.createInstance(this.getClass.getClassLoader)
  val dpf = DomainParticipantFactory.getInstance(env)
  val dp  = dpf.createParticipant
  val sub = dp.createSubscriber

  val topic: Topic[DDSFlightRecord] = dp.createTopic(opts.tn, classOf[DDSFlightRecord])
  val reader: DataReader[DDSFlightRecord] = sub.createDataReader(topic)

  val waitSet = WaitSet.newWaitSet(env)
  val dataState = sub.createDataState
  dataState.`with`(SampleState.NOT_READ)
  val cond = reader.createReadCondition(dataState)
  waitSet.attachCondition(cond)

  var terminate = false
  val thread = ThreadUtils.daemon {
    try {
      while (!terminate) {
        waitSet.waitForConditions
        val it = reader.take
        it.foreach { sample => println(FlightRecord.show(sample.getData)) }
        it.close
      }
    } catch {
      case x: Throwable => x.printStackTrace
    } finally {
      dp.close
    }
  }

  println(s"DDS client now reading dds.FlightRecord instances from topic: '${opts.tn}'")
  thread.start
  menu("enter command [9:exit]:\n") {
    case "9" =>
      terminate = true
      println("shutting down")
  }
}