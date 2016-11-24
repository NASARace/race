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

package gov.nasa.race.core

import akka.actor.{ActorRef, Terminated}
import com.typesafe.config.Config
import gov.nasa.race.core.Messages.{ChannelTopicAccept, ChannelTopicRelease, ChannelTopicRequest, ChannelTopicResponse}

import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}

/**
  * a SubscribingRaceActor that dynamically requests (channel,topic) pairs
  *
  * Note this is independent of the actual channel subscription, it merely regulates
  * what is transmitted over the channels we are subscribed to
  */
trait ChannelTopicSubscriber extends SubscribingRaceActor {

  // release provider once usage count goes to zero
  val subscriptions = MutableMap.empty[ChannelTopic,(Provider,Int)]
  val pendingRequests = MutableSet.empty[ChannelTopic]
  var isAllChannelRequest = false  // there can only be one at a time

  def requestChannelTopic(channelTopic: ChannelTopic):Unit = {
    pendingRequests += channelTopic
    info(s"$name sending request for $channelTopic")
    busFor(channelTopic.channel).publish( BusEvent(PROVIDER_CHANNEL,ChannelTopicRequest(channelTopic,self), self))
  }

  def request(channel: Channel, topic: Topic):Unit = requestChannelTopic(ChannelTopic(channel,topic))

  def requestTopic(topic: Topic):Unit = {
    isAllChannelRequest = true
    for (channel <- readFrom) request(channel,topic)
  }

  def handleCTSubscriberMessage: Receive = {
    case response: ChannelTopicResponse => processResponse(response)
  }

  override def handleSystemMessage: Receive = handleCTSubscriberMessage orElse super.handleSystemMessage

  def processResponse (response: ChannelTopicResponse) = {
    val channelTopic = response.channelTopic
    if (pendingRequests.contains(channelTopic)) {
      if (isResponseAccepted(response)) {
        acceptProvider(response)
        if (isAllChannelRequest) {
          pendingRequests.retain(_.topic != channelTopic.topic)
          isAllChannelRequest = false
        } else {
          pendingRequests -= channelTopic
        }
      }
    }
  }

  // used as a guard before calling acceptProvider. Override this if you are picky
  def isResponseAccepted(response: ChannelTopicResponse): Boolean = true

  // called from a ChannelProviderResponse handler, if we choose to accept
  def acceptProvider (response: ChannelTopicResponse) = {
    val channelTopic = response.channelTopic
    subscriptions.get(channelTopic) match {
      case Some((provider,n)) =>
        subscriptions += channelTopic -> (provider,n+1)
      case None =>
        response.provider ! response.toAccept(self)
        subscriptions += channelTopic -> (response.provider,1)
    }
  }

  // called whenever we decide to stop listening on the channel/topic
  def releaseChannelTopic(channelTopic: ChannelTopic): Boolean = {
    subscriptions.get(channelTopic) match {
      case Some((provider,n)) =>
        if (n == 1) {
          info(s"$name releasing $channelTopic")
          provider ! ChannelTopicRelease(channelTopic, self)
          subscriptions -= channelTopic
          return true
        } else {
          subscriptions += channelTopic -> (provider, n-1)
        }
      case None => // ignore
    }
    false // no release sent to provider
  }

  def release(channel: Channel, topic: Topic):Unit = releaseChannelTopic(ChannelTopic(channel,topic))

  def releaseTopic (topic: Topic) = {
    subscriptions.foreach { e =>
      e._1 match {
        case ct@ChannelTopic(_,`topic`) =>
          val provider = e._2._1
          info(s"$name releasing $ct")
          provider ! ChannelTopicRelease(ct, self)
        case other =>
      }
    }
  }

  // watch out - this releases all without looking at subscription counts
  // <2do> check this in the context of transitive providers
  def releaseAll: Boolean = {
    if (subscriptions.nonEmpty) {
      for (e <- subscriptions){
        val channelTopic = e._1
        val (provider,n) = e._2
        info(s"$name releasing $channelTopic")
        provider ! ChannelTopicRelease(channelTopic, self)
      }
      subscriptions.clear()
      true
    } else {
      false
    }
  }

  def hasSubscriptions = subscriptions.nonEmpty
}

/**
  * a publishing RaceActor that keeps track of registered clients
  *
  * The main purpose of this trait is to control publishing based on if there are registered
  * clients. In this sense, the most important user code facing method is the most simple
  * one - `hasClients()`
  *
  * In order to control which requests are responded to, the concrete actor has to implement
  * `isRequestAccepted(ChannelTopicRequest)`
  */
trait ChannelTopicProvider extends PublishingRaceActor {
  val clients = MutableSet.empty[ChannelTopicRelease] // the releases for all active clients

  def hasClients = clients.nonEmpty // used to control publishing

  def hasClientsForTopic[T] (topic: T): Boolean = {
    clients.exists(_ match {
      case ChannelTopicRelease(ChannelTopic(_, Some(`topic`)), _) => true
      case other => false
    })
  }

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Any = {
    super.onInitializeRaceActor(raceContext, actorConf)
    bus.subscribe(self, PROVIDER_CHANNEL) // this is where we get requests from
  }

  override def onTerminateRaceActor(originator: ActorRef): Any = {
    super.onTerminateRaceActor(originator)
    bus.unsubscribe(self, PROVIDER_CHANNEL)
  }

  def handleCTProviderMessage: Receive = {
    case BusEvent(PROVIDER_CHANNEL,request: ChannelTopicRequest,_) => processRequest(request)
    case accept: ChannelTopicAccept => processAccept(accept)
    case release: ChannelTopicRelease => processRelease(release)
    case Terminated(client) => clients.retain( release => release.client != client)
  }

  override def handleSystemMessage: Receive = handleCTProviderMessage orElse super.handleSystemMessage

  /**
    * reply to the requester if we /could/ serve the requested ChannelTopic. Note we don't
    * start to serve before we get an accept
    */
  def processRequest(request: ChannelTopicRequest) = {
    if (isRequestAccepted(request)) {
      request.requester ! request.toResponse(self)
    }
  }

  /**
    * has to be implemented by concrete provider to find out if we respond to a request
    */
  def isRequestAccepted(request: ChannelTopicRequest): Boolean

  /**
    * now we have a live client for the requested ChannelTopic
    */
  def processAccept(accept: ChannelTopicAccept) = {
    info(s"$name got $accept")
    clients += accept.toRelease
    context.watch(accept.client)
    gotAccept(accept) // just a notification, we can't reject it anymore
  }

  def processRelease(release: ChannelTopicRelease) = {
    info(s"$name got $release")
    clients -= release
    context.unwatch(release.client)
    gotRelease(release)
  }

  //--- those can be overridden in case the concrete provider has to perform its own house keeping
  def gotAccept (accept: ChannelTopicAccept) = {}
  def gotRelease (release: ChannelTopicRelease) = {}
}

/**
  * a ChannelTopicProvider that is itself a ChannelTopicSubscriber, i.e. only provides
  * the requested channelTopics if it can find itself a provider for its own input channels and the requested topic
  *
  * Note that mapping between input and output ChannelTopics is only done through the Topic if no explicit channel
  * mapping is provided
  */
trait TransitiveChannelTopicProvider extends ChannelTopicProvider with ChannelTopicSubscriber {
  val outInMap = MutableMap.empty[ChannelTopic,ChannelTopic] // maps output to input channel topics
  val responseForwards = MutableMap.empty[ChannelTopic,MutableSet[ChannelTopicRequest]]     // in -> {request}
  val acceptForwards = MutableMap.empty[ChannelTopic,ChannelTopicResponse]    // requester.accept -> provider.accept

  def handleTransitiveCTProviderMessage: Receive = {
    case BusEvent(PROVIDER_CHANNEL, request: ChannelTopicRequest, _) => processRequest(request)
    case response: ChannelTopicResponse => processResponse(response)
    case accept: ChannelTopicAccept => processAccept(accept)
    case release: ChannelTopicRelease => processRelease(release)
  }

  override def handleSystemMessage = handleTransitiveCTProviderMessage orElse super.handleSystemMessage

  /**
    *  handle a ChannelProviderRequest, directly replying to the requester if we already have the topic covered,
    *  andotherwise reaching out to providers for that topic ourselves
    */
  override def processRequest(req: ChannelTopicRequest) = {
    if (isRequestAccepted(req)) {
      val reqChannelTopic = req.channelTopic
      if (clients.find(_.channelTopic == reqChannelTopic).isDefined) { // we already serve it
        info(s"$name sending response for covered $req")
        req.requester ! req.toResponse(self)

      } else { // we have to request ourselves and keep track of how to forward responses
        for (ict <- mapToInputChannelTopics(reqChannelTopic)){
          responseForwards.getOrElseUpdate(ict, MutableSet.empty) += req
          requestChannelTopic(ict)
        }
      }
    }
  }

  /**
    * override if we have explicit output-to-input ChannelTopic mapping
    */
  def mapToInputChannelTopics (reqChannelTopic: ChannelTopic) = {
    readFrom.map( ChannelTopic(_,reqChannelTopic.topic))
  }

  /**
    * handle a ChannelProviderResponse by responding to registered requesters, but don't accept our provider yet until
    * we get a ChannelProviderAccept from one of the notified requesters. Store acceptable providers so that we
    * can accept one of them once we get an accept from a notified requester ourselves
    */
  override def processResponse (response: ChannelTopicResponse) = {
    val rspChannelTopic = response.channelTopic
    responseForwards.get(rspChannelTopic) match {
      case Some(reqs) =>
        for (req <- reqs) {
          req.requester ! req.toResponse(self)
          acceptForwards += req.channelTopic -> response
        }
        responseForwards -= rspChannelTopic
      case None =>
        super.processResponse(response)
    }
  }

  override def processAccept (accept: ChannelTopicAccept) = {
    super.processAccept(accept)

    val channelTopic = accept.channelTopic
    acceptForwards.get(channelTopic) match {
      case Some(rsp) =>
        acceptProvider(rsp)
        acceptForwards -= channelTopic
        outInMap += channelTopic -> rsp.channelTopic
      case None =>
    }
  }

  override def processRelease (rel: ChannelTopicRelease) = {
    val outChannelTopic = rel.channelTopic
    outInMap.get(outChannelTopic) match {
      case Some(inChannelTopic) =>
        if (releaseChannelTopic(inChannelTopic)) outInMap -= outChannelTopic
      case _ => // ignore
    }
    super.processRelease(rel) // takes care of updating clients
  }
}
