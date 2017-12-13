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

import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.ThreadUtils
import gov.nasa.race.util.ConsoleIO._
import org.omg.dds.core.{ServiceEnvironment, WaitSet}
import org.omg.dds.domain.DomainParticipantFactory
import org.omg.dds.sub.{DataReader, SampleState}
import org.omg.dds.topic.Topic

import scala.collection.JavaConverters._

/**
  * simple test client that reads FlightRecord instances from a DDS topic
  */
object DDSClient {

  class Opts extends CliArgs("ddsclient") {
    var tn: String = "Flight"
    var dds: String = "com.prismtech.cafe.core.ServiceEnvironmentImpl"

    opt1("--topic")("<topicName>", s"DDS topic to subscribe to (default=$tn)") { a => tn = a }
    opt1("--dds")("<className>", s"DDS ServiceEnvironment class (default=$dds)") { a => dds = a }
  }

  def main (args: Array[String]): Unit = {
    val opts = new Opts
    if (!opts.parse(args)) return

    System.setProperty("logback.configurationFile", "logback-ddsclient.xml")
    System.setProperty(ServiceEnvironment.IMPLEMENTATION_CLASS_NAME_PROPERTY, opts.dds)

    val env = ServiceEnvironment.createInstance(this.getClass.getClassLoader)
    val dpf = DomainParticipantFactory.getInstance(env)
    val dp = dpf.createParticipant
    val sub = dp.createSubscriber

    val topic: Topic[dds.FlightRecord] = dp.createTopic(opts.tn, classOf[dds.FlightRecord])
    val reader: DataReader[dds.FlightRecord] = sub.createDataReader(topic)

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
          it.asScala.foreach { sample => println(FlightRecord.show(sample.getData)) }
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
}