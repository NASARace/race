/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.util.ByteString
import com.typesafe.config.Config
import gov.nasa.race.uom.Angle._
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.air.translator.OpenSkyParser
import gov.nasa.race.archive.TaggedASCIIArchiveWriter
import gov.nasa.race.core.ContinuousTimeRaceActor
import gov.nasa.race.http.HttpImportActor

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

/**
  * a specialized HttpImportActor for OpenSky-network requests
  *
  * potential queries are defined on https://opensky-network.org/apidoc/rest.html
  */
class OpenSkyImportActor (config: Config) extends HttpImportActor (config) with FilteringPublisher {

  //--- the request bounding box
  val minLat = Degrees(config.getDouble("min-lat"))
  val maxLat = Degrees(config.getDouble("max-lat"))
  val minLon = Degrees(config.getDouble("min-lon"))
  val maxLon = Degrees(config.getDouble("max-lon"))

  val host = config.getStringOrElse("host", "https://opensky-network.org")
  val flatten = config.getBooleanOrElse("flatten", false)

  val parser = new OpenSkyParser
  parser.setElementsReusable(flatten) // only reuse elements if we flatten results

  override def defaultTickInterval: FiniteDuration = 5.seconds // we want periodic updates

  override def createRequests (conf: Config): Seq[HttpRequest] = {
    val uri = f"$host/api/states/all?lamin=${minLat.toNormalizedDegrees180}%.3f&lomin=${minLon.toNormalizedDegrees180}%.3f&lamax=${maxLat.toNormalizedDegrees180}%.3f&lomax=${maxLon.toNormalizedDegrees180}%.3f"
    Seq(HttpRequest(HttpMethods.GET, uri))
  }

  override protected def processResponseData(body: ByteString): Unit = {
    val bs = body.toArray // unfortunately a copying version
    //val bs = body.asByteBuffer.array // does not work since HeapByteBuffer is readonly

    val res = parser.parse(bs)
    if (res.nonEmpty){
      if (flatten) {
        res.foreach(publishFiltered)
      } else {
        publishFiltered(res)
      }
    }
  }
}

class OpenSkyArchiveActor (conf: Config) extends OpenSkyImportActor(conf) with ContinuousTimeRaceActor {
  val writer = new TaggedASCIIArchiveWriter(config)

  override def onStartRaceActor (originator: ActorRef) = {
    writer.open(baseSimTime)
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    writer.close()
    super.onTerminateRaceActor(originator)
  }

  override protected def processResponseData(body: ByteString): Unit = {
    val bs = body.toArray
    writer.write(currentSimTime,bs)
  }
}
