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

package gov.nasa.race.actors.imports

import com.typesafe.config.{ConfigFactory, Config}
import gov.nasa.race.actors.FilteringPublisher
import gov.nasa.race.common._
import gov.nasa.race.core.Messages.{ChannelTopicRelease, ChannelTopicAccept, ChannelTopicRequest}
import gov.nasa.race.core._
import gov.nasa.race.data.Airport

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

/**
  * trait to handle conditional ASDE-X imports, filtered by requested airports
  */
trait AsdexImporter extends ChannelTopicProvider with FilteringPublisher {

  // we split this into two data structures since we need efficient iteration over the regexes
  // whereas serving/unserving airports happens rarely.
  // <2do> compare runtime behavior against TrieMap, which would be less/cleaner code
  var servedAirports = Map.empty[Airport,Regex]
  val regexes = ArrayBuffer[Regex]() // simple pattern regexes in Java use Boyer Moore, which is faster than indexOf()

  class AirportFilter(val config: Config) extends ConfigurableFilter {
    // note this is executed from a non-actor thread so iteration also needs to be thread safe.
    // <2do> iteration should execute in constant space since this can have a high call frequency
    override def pass(msg: Any): Boolean = {
      msg match {
        case txt: String =>
          regexes.synchronized {
            if (regexes.exists(re => re.findFirstIn(txt).nonEmpty)) return true
          }
        case _ =>
      }
      false
    }
  }

  //--- the FilteringPublisher interface
  override def createFilters (config: Config) =  Seq[ConfigurableFilter](new AirportFilter(ConfigFactory.empty))
  override def letPassUnfiltered (config: Config) = false // no filter, no publishing

  //--- publishing support
  // in response to a gotAccept()
  def serve(airport: Airport) = {
    if (airport.hasAsdex){
      if (!servedAirports.contains(airport)){
        regexes.synchronized {
          val regEx = s"<airport>${airport.id}</airport>".r
          info(s"start serving airport ${airport.id}")
          regexes += regEx
          servedAirports = servedAirports + (airport -> regEx)
        }
      }
    }
  }
  def serveId (airportId: String) = ifSome(Airport.asdexAirports.get(airportId))( serve(_))

  // in response to a gotRelease
  def unserve (airport: Airport) = {
    ifSome(servedAirports.get(airport)) { r =>
      regexes.synchronized {
        info(s"stop serving airport ${airport.id}")
        regexes -= r
        servedAirports = servedAirports - airport
      }
    }
  }
  def unserveId (airportId: String) = ifSome(Airport.asdexAirports.get(airportId))( unserve(_))


  //--- the ChannelTopicProvider interface

  // this is a boundary actor - we get our data from outside RACE so there is nobody to request from
  override def isRequestAccepted (request: ChannelTopicRequest) = {
    val channelTopic = request.channelTopic
    if (writeTo.contains(channelTopic.channel)){
      channelTopic.topic match {
        case Some(airport:Airport) => Airport.asdexAirports.contains(airport.id)
        case Some(airportId:String) => Airport.asdexAirports.contains(airportId)
        case _ => false
      }
    } else false
  }

  override def gotAccept (accept: ChannelTopicAccept) = {
    accept.channelTopic.topic match {
      case Some(airport: Airport) => serve(airport)
      case Some(airportId: String) => serveId(airportId)
      case _ => // ignore, we only serve airports
    }
  }

  override def gotRelease (release: ChannelTopicRelease) = {
    release.channelTopic.topic match {
      case Some(airport: Airport) => unserve(airport)
      case Some(airportId: String) => unserveId(airportId)
      case _ => // ignore, we only serve airports
    }
  }
}
