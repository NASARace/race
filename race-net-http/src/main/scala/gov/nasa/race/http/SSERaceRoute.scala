/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import akka.actor.Actor.Receive
import akka.Done
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.sse.ServerSentEvent

import scala.concurrent.duration._
import akka.http.scaladsl.server.Directives.{complete, headerValueByType}
import akka.stream.Materializer
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}

import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.collection.Seq
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, RaceDataClient}
import gov.nasa.race.ifSome


/**
  * RaceRoute that supports server sent events (SSE) as a unidirectional push mechanism
  *
  * this is similar to a WSRaceRoute without the need to handle client messages
  *
  * note it is up to the concrete type to translate events into SSEs from within their receiveData handler
  */
trait SSERaceRoute extends RaceRouteInfo {

  implicit val materializer: Materializer = HttpServer.materializer
  implicit val ec = HttpServer.ec // we use the server execution context

  protected def promoteToStream(): Route

  protected def initializeConnection (queue: SourceQueueWithComplete[ServerSentEvent]): Unit = {
    // nothing here
  }

}

/**
  * SSERaceRoute that pushes messages received from a bus channel to all connected clients
  *
  * Note it is still up to the concrete type to provice a receiveData() implementation that handles relevant
  * data, which then has to be translated and pushed like this:  ..toSSE(data).foreach(push)
  */
trait PushSSERoute extends SSERaceRoute with SourceQueueOwner[ServerSentEvent] with RaceDataClient {

  val keepAliveInterval = config.getFiniteDurationOrElse("keep-alive", 5.seconds)
  protected var connections: Map[InetSocketAddress,SourceQueueWithComplete[ServerSentEvent]] = Map.empty

  def hasConnections: Boolean = connections.nonEmpty

  protected def push (m: ServerSentEvent): Unit = synchronized {
    connections.foreach (e => pushTo(e._1,e._2, m))
  }

  protected def pushTo (remoteAddr: InetSocketAddress, m: ServerSentEvent): Unit = synchronized {
    ifSome(connections.get(remoteAddr)) { queue=>
      pushTo(remoteAddr,queue,m)
    }
  }

  protected def pushTo(remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[ServerSentEvent], m: ServerSentEvent): Unit = synchronized {
    queue.offer(m).onComplete {
      case Success(_) => // all good (TODO should we check for Enqueued here?)
        info(s"pushing message $m to $remoteAddr")

      case Failure(_) =>
        info(s"dropping connection: $remoteAddr")
        connections = connections.filter( e => e._1 ne remoteAddr)
    }
  }

  protected def handleConnectionLoss (remoteAddress: InetSocketAddress, cause: Any): Unit = {
    connections = connections.filter( e=> e._1 != remoteAddress)
    info(s"connection closed: $remoteAddress, cause: $cause")
  }

  protected def promoteToStream(): Route = {
    import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

    headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
      val remoteAddress = sockConn.remoteAddress // this is a stable address since the response connection is kept open

      val (outboundMat,outbound) = createPreMaterializedSourceQueue
      val newConn = (remoteAddress, outboundMat)
      initializeConnection(outboundMat)
      connections = connections + newConn

      val completionFuture: Future[Done] = outboundMat.watchCompletion()
      completionFuture.onComplete { handleConnectionLoss(remoteAddress,_) }

      complete( outbound)
    }
  }

  override def createSourceQueue: Source[ServerSentEvent, SourceQueueWithComplete[ServerSentEvent]] = {
    Source.queue[ServerSentEvent](srcBufSize, srcPolicy).keepAlive( keepAliveInterval, () => ServerSentEvent.heartbeat)
  }
}
