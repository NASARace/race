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

import gov.nasa.race.geo.{GreatCircle, GeoPosition}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.ThreadUtils
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.uom._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed._
import scala.concurrent.duration._

import org.omg.dds.core.ServiceEnvironment
import org.omg.dds.domain.DomainParticipantFactory
import org.omg.dds.pub.DataWriter
import org.omg.dds.topic.Topic


/**
  * simple test producer that publishes FlightRecord instances to a DDS topic
  */
object DDSServer {


  class Opts extends CliArgs("ddsserver") {
    var tn: String = "Flight"
    var interval: Int = 3000
    var cs: String = "X42"
    var lat: Double = 37.246822
    var lon: Double = -121.9770277
    var hdg: Double = 84.0
    var alt: Double = 5000
    var spd: Double = 350.0
    var dds: String = "com.prismtech.cafe.core.ServiceEnvironmentImpl"

    opt1("--topic")("<topicName>", s"DDS topic to subscribe to (default=$tn)") { a => tn = a }
    opt1("--dds")("<className>", s"DDS ServiceEnvironment class (default=$dds)") { a => dds = a }
    opt1("--interval")("<milliseconds>", s"send interval (default=$interval"){a=> interval = parseInt(a)}
    opt1("--cs")("<callsign>","call sign for generated flight"){a=> cs = a}
    opt1("--lat")("<degrees>", "initial latitude of generated flight"){a=> lat = parseDouble(a)}
    opt1("--lon")("<degrees>", "initial longitude of generated flight"){a=> lon = parseDouble(a)}
    opt1("--hdg")("<degrees>", "initial heading of generated flight"){a=> hdg = parseDouble(a)}
    opt1("--alt")("<feet>", "initial altitude of generated flight"){a=> alt = parseDouble(a)}
    opt1("--spd")("<knots>", "initial speed of generated flight"){a=> spd = parseDouble(a)}
  }

  def main(args: Array[String]): Unit = {
    val opts = new Opts
    if (!opts.parse(args)) return

    System.setProperty("logback.configurationFile", "logback-ddsserver.xml")
    System.setProperty(ServiceEnvironment.IMPLEMENTATION_CLASS_NAME_PROPERTY, opts.dds)

    val env = ServiceEnvironment.createInstance(this.getClass.getClassLoader)
    val dpf = DomainParticipantFactory.getInstance(env)
    val dp = dpf.createParticipant
    val pub = dp.createPublisher

    val topic: Topic[dds.FlightRecord] = dp.createTopic(opts.tn, classOf[dds.FlightRecord])
    val writer: DataWriter[dds.FlightRecord] = pub.createDataWriter(topic)
    var terminate = false

    val thread = ThreadUtils.daemon {
      var tLast = System.currentTimeMillis
      val fr = new dds.FlightRecord(opts.cs, opts.lat, opts.lon, opts.alt, opts.spd, opts.hdg, tLast)

      while (!terminate) {
        println(FlightRecord.show(fr))
        writer.write(fr)

        Thread.sleep(opts.interval)
        val t = System.currentTimeMillis
        update(fr, t, tLast)
        tLast = t
      }

      dp.close
    }

    println(s"DDS server now publishing dds.FlightRecord instances to topic: '${opts.tn}'")

    thread.start
    menu("enter command [9:exit]:\n") {
      case "9" =>
        terminate = true
        println("shutting down")
    }
  }

  def update (fr: dds.FlightRecord, t: Long, tLast: Long): Unit = {
    import fr._
    val pos = GeoPosition.fromDegrees(lat,lon)
    val dist: Length = Knots(speed) * ((t - tLast)/1000.0).seconds
    val pos1 = GreatCircle.endPos(pos, dist, Degrees(heading), Feet(alt))

    lat = pos1.φ.toDegrees
    lon = pos1.λ.toDegrees
    date = t
  }
}