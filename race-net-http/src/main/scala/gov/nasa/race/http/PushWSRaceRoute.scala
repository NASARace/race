/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.http

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.QueueOfferResult
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl.{Flow, Sink, SourceQueueWithComplete}
import akka.{Done, NotUsed}
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.core.{Ping, Pong, RaceDataClient}
import gov.nasa.race.ifSome
import gov.nasa.race.uom.Time.Seconds
import gov.nasa.race.uom.{DateTime, Time}

import java.net.InetSocketAddress
import scala.collection.mutable.{Map => MutMap}
import scala.concurrent.Future
import scala.util.{Failure, Success}


/**
  * a WSRaceRoute that can react to both client messages and push own content (e.g. messages received from the RACE bus).
  *
  * Note this kind of websocket route needs to keep track of connections and therefore imposes a scaling constraint
  */
trait PushWSRaceRoute extends WSRaceRoute with SourceQueueOwner[Message] {

  protected var connections: Map[InetSocketAddress,SourceQueueWithComplete[Message]] = Map.empty

  def hasConnections: Boolean = connections.nonEmpty

  // what to send to clients upon opening the websocket
  protected def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {} // nothing

  protected def createFlow(ctx: WSContext): Flow[Message,Message,NotUsed] = {
    val sockConn = ctx.sockConn
    val remoteAddress = sockConn.remoteAddress // this is a stable (websocket) address

    val (outboundMat,outbound) = createPreMaterializedSourceQueue
    val newConn = (remoteAddress, outboundMat)
    initializeConnection(ctx,outboundMat)
    connections = connections + newConn

    val completionFuture: Future[Done] = outboundMat.watchCompletion()
    completionFuture.onComplete { handleConnectionLoss(remoteAddress,_) } // TODO does this handle passive network failures?

    val inbound: Sink[Message,Any] = Sink.foreach{ inMsg =>
      try {
        handleIncoming( ctx, inMsg).foreach( outMsg => {
          pushTo(remoteAddress, outboundMat, outMsg)
        })
      } catch {
        case t: Throwable => warning(s"error processing incoming websocket message: $t")
      }
    }

    Flow.fromSinkAndSourceCoupled(inbound, outbound)
  }

  protected def push (m: Message): Unit = synchronized {
    connections.foreach (e => pushTo(e._1,e._2, m))
  }

  /**
    * NOTE - this only pushed to a connected remoteAddr
    * don't use in initializeConnection() which is called before the new client is added to connections
    */
  protected def pushTo (remoteAddr: InetSocketAddress, m: Message): Unit = synchronized {
    ifSome(connections.get(remoteAddr)) { queue=>
      pushTo(remoteAddr,queue,m)
    }
  }

  /**
    * use this to send from initializeConnection(), which is called before updating connections hence we need
    * to specify the queue explicitly
    *
    * TODO - rename to make this more prominent
    */
  protected def pushTo(remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message], m: Message): Unit = synchronized {
    queue.offer(m).onComplete {
      case Success(v) =>
        v match {
          case QueueOfferResult.Enqueued => // all good
            info(s"pushing message $m to $remoteAddr")
            //println(s"@@@@ enqueued message ${m.toString.substring(0,55)} to $remoteAddr")

          case QueueOfferResult.Dropped =>
            warning(s"dropped message to: $remoteAddr")
            //println(s"@@@@ DROPPED message to: $remoteAddr")

          case QueueOfferResult.QueueClosed =>
            warning(s"connection closed: $remoteAddr")
            //println(s"@@@@ CLOSED connection: $remoteAddr")
            connections = connections.filter(e => e._1 ne remoteAddr)

          case QueueOfferResult.Failure(f) =>
            warning(s"dropping connection: $remoteAddr because of $f")
            //println(s"@@@@ dropping connection: $remoteAddr because of $f")
            connections = connections.filter( e => e._1 ne remoteAddr)
        }

      case Failure(e) =>
        error(s"failed to push message \"${m.toString.substring(0,55)}...\" to $remoteAddr: $e")
          //println("@@@ FAILURE: ${m.toString.substring(0,55)} => $e")
    }
  }

  protected def pushToFiltered (m: Message)( f: InetSocketAddress=>Boolean): Unit = synchronized {
    connections.foreach { e=>
      if (f(e._1)) pushTo(e._1, e._2,m)
    }
  }

  /**
    * override if concrete routes need to do more cleanup
    */
  protected def handleConnectionLoss (remoteAddress: InetSocketAddress, cause: Any): Unit = {
    connections = connections.filter( e=> e._1 != remoteAddress)
    info(s"connection closed: $remoteAddress, cause: $cause")
  }
}

/**
  * a WSRaceRoute that periodically pings its connections and closes the ones that don't respond properly
  * note this also can be used to keep the WS alive if we own the client
  */
trait MonitoredPushWSRaceRoute extends PushWSRaceRoute {

  protected var nPings = 0
  protected val pingWriter = new JsonWriter(JsonWriter.RawElements, 512)

  protected var monitorInterval: Time = Seconds(30)
  protected val pingResponses: MutMap[InetSocketAddress, DateTime] = MutMap.empty

  val monitorThread = new Thread {
    setDaemon(true)

    override def run(): Unit = {
      while (true) {
        checkLiveConnections()
        Thread.sleep(monitorInterval.toMillis)
      }
    }
  }

  monitorThread.start

  def checkLiveConnections(): Unit = synchronized {
    val now = DateTime.now
    val pingMsg = TextMessage(pingWriter.toJson(Ping(name, "client", nPings + 1, now)))

    connections.foreach { con =>
      val ipAddr = con._1
      info(s"pinging connection $ipAddr")
      pushTo(ipAddr, pingMsg)

      pingResponses.get(ipAddr) match {
        case Some(dtg) =>
          if (now.timeSince(dtg) > monitorInterval) { // no response
            handleConnectionLoss(ipAddr, "no ping response")
          }
        case None => // new connection, add initial pseudo-entry
          pingResponses += ipAddr -> DateTime.Date0
      }
    }
    nPings += 1
  }

  override protected def handleConnectionLoss (remoteAddress: InetSocketAddress, cause: Any): Unit = synchronized {
    pingResponses -= remoteAddress
    super.handleConnectionLoss(remoteAddress,cause)
  }

  // we don't provide a handleIncoming() implementation that processes Pongs since instances already have
  // a parser that can be easily extended and might have to react specifically. If there is no specialized handling
  // it can just call  the following handlePong()

  def handlePong (ipAddr: InetSocketAddress, pong: Pong): Unit = synchronized {
    pingResponses += (ipAddr -> DateTime.now)  // we use our local time instead of the pong reply to account for clock skew (user clients might not be synced)
  }
}
