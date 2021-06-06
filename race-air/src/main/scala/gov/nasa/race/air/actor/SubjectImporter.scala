/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import com.typesafe.config.{Config, ConfigFactory}
import gov.nasa.race._
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.core.{ChannelTopicAccept, ChannelTopicRelease, ChannelTopicRequest}
import gov.nasa.race.core.ChannelTopicProvider

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

/**
  * a ChannelTopicProvider for generic subjects that can be filtered by means of subject specific regexes
  */
trait SubjectImporter[T] extends ChannelTopicProvider with FilteringPublisher {

  // we split this into two data structures since we need efficient iteration over the regexes
  // whereas serving/unserving airports happens rarely.
  // <2do> compare runtime behavior against TrieMap, which would be less/cleaner code
  var servedSubjects = Map.empty[T,Regex]
  val regexes = ArrayBuffer[Regex]() // simple pattern regexes in Java use Boyer Moore, which is faster than indexOf()

  class SubjectFilter(val config: Config) extends ConfigurableFilter {
    // note this is executed from a non-actor thread so iteration also needs to be thread safe.
    // <2do> iteration should execute in constant space since this can have a high call frequency
    override def pass(msg: Any): Boolean = {
      msg match {
        case txt: String =>
          regexes.synchronized {
            regexes.exists(re => re.findFirstIn(txt).nonEmpty)
          }
        case _ => false
      }
    }
  }

  //--- provided by concrete type
  def topicSubject (topic: Any): Option[T]     // get the optional subject instance for a generic topic

  // TODO - filtering should be abstract since regex implies there is text message input
  def subjectRegex(subject: T): Option[Regex]  // get the optional regex for a subject

  override def handleMessage = handleFilteringPublisherMessage

  //--- the FilteringPublisher interface
  override def createFilters =  Array[ConfigurableFilter](new SubjectFilter(ConfigFactory.empty))
  override def passUnfilteredDefault = false // no filter, no publishing

  //--- publishing support

  // in response to a gotAccept()
  def serve(subject: T) = {
      if (!servedSubjects.contains(subject)){
        regexes.synchronized {
          subjectRegex(subject) match {
            case Some(r) =>
              info(s"start serving $subject")
              regexes += r
              servedSubjects = servedSubjects + (subject -> r)
            case None =>
              info(s"no regex for $subject")
          }
        }
      }
  }

  // in response to a gotRelease
  def unserve (subject: T) = {
    ifSome(servedSubjects.get(subject)) { r =>
      regexes.synchronized {
        info(s"stop serving $subject")
        regexes -= r
        servedSubjects = servedSubjects - subject
      }
    }
  }

  //--- the ChannelTopicProvider interface

  // this is a boundary actor - we get our data from outside RACE so there is nobody to request from
  override def isRequestAccepted (request: ChannelTopicRequest) = {
    val channelTopic = request.channelTopic
    if (writeTo.contains(channelTopic.channel)){
      topicSubject(channelTopic.topic).isDefined
    } else false
  }

  override def gotAccept (accept: ChannelTopicAccept) = {
    ifSome(topicSubject(accept.channelTopic.topic)){ serve }
  }

  override def gotRelease (release: ChannelTopicRelease) = {
    ifSome(topicSubject(release.channelTopic.topic)){ unserve }
  }
}
