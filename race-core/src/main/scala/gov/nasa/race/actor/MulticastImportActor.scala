/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.actor

import java.net.{DatagramPacket, InetAddress, InetSocketAddress, MulticastSocket, NetworkInterface}
import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.ExecutionContext.Implicits.global
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.common.{ByteBufferReader, Status}
import gov.nasa.race.core.Publish
import gov.nasa.race.util.ThreadUtils

import java.nio.ByteBuffer

/**
  * a filtering publisher that imports messages from a multicast socket
  * the actor needs to be configured with a 'reader' that understands the received messages
  *
  * Note - if this actor has a 'interval' configuration > 0 (in milliseconds) we do not publish when we
  * receive but accumulate received IdentifiableObjects, to be published in the specified interval.
  * OTHER ITEMS ARE DISCARDED IN THIS CASE (we wouldn't know what keys to use)
  */
class MulticastImportActor (val config: Config) extends FilteringPublisher {
  val MaxMsgLen: Int = 1600

  //--- this is using multicast so we need a group ip address /port
  val netIfc = NetworkInterface.getByName( config.getString("multicast-interface"))
  val groupAddr = InetAddress.getByName( config.getString("group-address"))
  val groupPort = config.getIntOrElse("group-port", 4242)
  val sockAddr = new InetSocketAddress(groupAddr,groupPort)

  val reader: ByteBufferReader = createReader

  //--- if interval == 0 we publish as soon as we get the packets
  val publishInterval = config.getFiniteDurationOrElse("interval", 0.milliseconds)

  var publishScheduler: Option[Cancellable] = None
  val publishItems = MHashMap.empty[String,Any] // used to store publish items in case we have a explicit publishInterval

  // if this is set we only receive updates about the same items and hence we don't have to accumulate/store
  // but only check if the duration between receive and lastPublish exceeds the publishInterval
  // this is a significant optimization because we don't need a scheduler or a publishItems map in this case
  val sameItems = config.getBooleanOrElse("same-items", false)

  val socket = createSocket
  var receiverThread: Thread = ThreadUtils.daemon(runReceiver)


  def createSocket = {
    val sock = new MulticastSocket(groupPort)
    sock.setReuseAddress(true)
    sock
  }

  protected def createReader: ByteBufferReader = getConfigurable[ByteBufferReader]("reader")

  override def onStartRaceActor(originator: ActorRef) = {
    receiverThread.start
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(publishScheduler){ _.cancel() }
    receiverThread.interrupt
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = {
    case Publish => publishStoredItems
  }

  def publishStoredItems = publishItems.synchronized {
    if (publishItems.nonEmpty) {
      publishItems.values.foreach(publishFiltered)
      publishItems.clear()
    }
  }

  def storeItems (items: Seq[_]) = publishItems.synchronized {
    items.foreach { item =>
      item match {
        case it: IdentifiableObject => publishItems += it.id -> it
        case _ => // don't know how to store (perhaps warning?)
      }
    }
  }

  def storeItem (item: Any) = publishItems.synchronized {
    item match {
      case it: IdentifiableObject => publishItems += it.id -> it
      case _ => // don't know how to store (perhaps warning?)
    }
  }

  // NOTE - this is executed in a separate thread, beware of race conditions
  def runReceiver: Unit = {
    val publishMillis = publishInterval.toMillis
    var lastPublish: Long = 0

    def storeOrPublish (storeOp: => Unit, publishOp: =>Unit): Unit = {
      if (publishScheduler.isDefined) {
        storeOp
      } else {
        val tNow = System.currentTimeMillis
        if ((tNow - lastPublish) > publishMillis ) {
          publishOp
          lastPublish = tNow
        }
      }
    }

    try {
      socket.joinGroup(sockAddr,netIfc)

      if (publishInterval.length > 0 && !sameItems) {
        info(s"starting publish scheduler $publishInterval")
        publishScheduler = Some(scheduler.scheduleWithFixedDelay(0.seconds, publishInterval, self, Publish))
      }

      info(s"joined multicast $groupAddr")
      val bb = ByteBuffer.allocate(MaxMsgLen)
      val packet = new DatagramPacket(bb.array, bb.capacity)

      while (status == Status.Running) {
        bb.clear()
        socket.receive(packet)
        bb.limit(packet.getLength)

        reader.read(bb) match {
          case Some(items: Seq[_]) => storeOrPublish(storeItems(items),items.foreach(publishFiltered))
          case Some(item) => storeOrPublish(storeItem(item),publishFiltered(item))
          case None => // do nothing
        }
      }
    } finally {
      socket.leaveGroup(sockAddr,netIfc)
      socket.close
    }
  }
}
