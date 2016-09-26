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

package gov.nasa.race.ddsserver


import dds.{FlightRecord => DDSFlightRecord}
import gov.nasa.race.common.ConsoleIO._
import gov.nasa.race.common.ThreadUtils
import org.omg.dds.core.ServiceEnvironment
import org.omg.dds.domain.DomainParticipantFactory
import org.omg.dds.pub.DataWriter
import org.omg.dds.topic.Topic
import scopt.OptionParser
import gov.nasa.race.data._
import gov.nasa.race.data.GreatCircle._
import gov.nasa.race.data.dds.FlightRecord
import squants.motion.Knots
import squants.space.{Degrees, Feet, Length}
import squants.time.Seconds


/**
  * simple test producer that publishes FlightRecord instances to a DDS topic
  */
object MainSimple extends App {

  case class CliOpts (
    tn: String = "Flight",
    interval: Int = 3000,
    cs: String = "X42",
    lat: Double = 37.246822,
    lon: Double = -121.9770277,
    hdg: Double = 84.0,
    alt: Double = 5000,
    spd: Double = 350.0,
    dds: String = "com.prismtech.cafe.core.ServiceEnvironmentImpl"
  )

  def cliParser = {
    new OptionParser[CliOpts]("ddsserver") {
      help("help") abbr ("h") text ("print this help")
      opt[String]("topic") text ("topic name") optional() action {(v, o) => o.copy(tn = v)}
      opt[Int]("interval") text ("interval [msec]") optional() action {(v, o) => o.copy(interval = v)}
      opt[String]("cs") text ("callsign") optional() action {(v, o) => o.copy(cs = v)}
      opt[Double]("lat") text ("initial latitude") optional() action {(v, o) => o.copy(lat = v)}
      opt[Double]("lon") text ("initial longitude") optional() action {(v, o) => o.copy(lon = v)}
      opt[Double]("hdg") text ("heading") optional() action {(v, o) => o.copy(hdg = v)}
      opt[Double]("alt") text ("altitude [ft]") optional() action {(v, o) => o.copy(alt = v)}
      opt[Double]("spd") text ("speed [kn]") optional() action {(v, o) => o.copy(spd = v)}
      opt[String]("dds") text ("DDS implementation class") optional() action {(v, o) => o.copy(dds = v)}
    }
  }
  val opts: CliOpts = cliParser.parse(args, CliOpts()).get

  System.setProperty("logback.configurationFile", "logback-ddsserver.xml")
  System.setProperty(ServiceEnvironment.IMPLEMENTATION_CLASS_NAME_PROPERTY,opts.dds)

  val env = ServiceEnvironment.createInstance(this.getClass.getClassLoader)
  val dpf = DomainParticipantFactory.getInstance(env)
  val dp  = dpf.createParticipant
  val pub = dp.createPublisher

  val topic: Topic[DDSFlightRecord] = dp.createTopic(opts.tn, classOf[DDSFlightRecord])
  val writer: DataWriter[DDSFlightRecord] = pub.createDataWriter(topic)
  var terminate = false

  val thread = ThreadUtils.daemon {
    var tLast = System.currentTimeMillis
    val fr = new DDSFlightRecord(opts.cs, opts.lat, opts.lon, opts.alt, opts.spd, opts.hdg, tLast)

    while (!terminate) {
      println(FlightRecord.show(fr))
      writer.write(fr)

      Thread.sleep(opts.interval)
      val t = System.currentTimeMillis
      update(fr,t,tLast)
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

  def update (fr: DDSFlightRecord, t: Long, tLast: Long): Unit = {
    import fr._
    val pos = LatLonPos.fromDegrees(lat,lon)
    val dist: Length = Knots(speed) * Seconds((t - tLast)/1000.0)
    val pos1 = endPos(pos, dist, Degrees(heading), Feet(alt))

    lat = pos1.φ.toDegrees
    lon = pos1.λ.toDegrees
    date = t
  }
}