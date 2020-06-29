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

import java.net.InetSocketAddress
import akka.NotUsed
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import com.typesafe.config.ConfigException
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceDataConsumer

import scala.collection.immutable.Iterable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * root type for WebSocket RaceRouteInfos
  */
trait WSRaceRoute extends RaceRouteInfo {
  final implicit val materializer: Materializer = Materializer.createMaterializer(parent.context)

  protected def promoteToWebSocket: Route
}

case class WebSocketPushConnection(queue: SourceQueueWithComplete[Message], remoteAddr: InetSocketAddress)

/**
  * a RaceRoute that completes with a WebSocket to which messages are pushed from an
  * associated actor that received data from RACE channels and turns them into web socket Messages
  */
trait PushWSRaceRoute extends WSRaceRoute with RaceDataConsumer {

  val srcBufSize = config.getIntOrElse("source-queue", 16)
  val srcPolicy = config.getStringOrElse( "source-policy", "dropTail") match {
    case "dropHead" => OverflowStrategy.dropHead
    case "dropNew" => OverflowStrategy.dropNew
    case "dropTail" => OverflowStrategy.dropTail
    case "dropBuffer" => OverflowStrategy.dropBuffer
    case "fail" => OverflowStrategy.fail
    case other => throw new ConfigException.Generic(s"unsupported source buffer overflow strategy: $other")
  }

  private var connections: List[WebSocketPushConnection] = List.empty

  protected def push (m: Message): Unit = synchronized {
    connections.foreach(pushTo(_,m))
  }

  protected def pushTo( conn: WebSocketPushConnection, m: Message): Unit = synchronized {
    implicit val ec = scala.concurrent.ExecutionContext.global

    conn.queue.offer(m).onComplete { res=>
      res match {
        case Success(_) => // all good (TODO should we check for Enqueued here?)
          info(s"pushing message $m to ${conn.remoteAddr}")

        case Failure(_) =>
          info(s"dropping connection: ${conn.remoteAddr}")
          connections = connections.filter( _ ne conn)
      }
    }
  }

  /**
    * called by associated actor
    * NOTE - this is executed from the actor thread and can modify connections so we have to synchronize
    */
  def setData (data: Any): Unit = {
    push(TextMessage.Strict(data.toString))
  }

  protected def discardMessage (m: Message): Unit = {
    m match {
      case tm: TextMessage => tm.textStream.runWith(Sink.ignore)
      case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore)
    }
  }

  /**
    * handle incoming message
    * override in subclasses if incoming messages need to be processed - default is doing nothing
    * note this executes in a synchronized context
    */
  protected def handleIncoming (m: Message): Iterable[Message] = {
    info(s"ignoring incoming message $m")
    discardMessage(m)
    Nil
  }

  /**
    * override in concrete routes to push initial data etc.
    * Use pushTo
    */
  protected def initializeConnection (conn: WebSocketPushConnection): Unit = {
    // nothing here
  }

  /**
    * complete routes that create web-sockets with this call
    * NOTE - this might execute overlapping with push(), hence we synchronize
    */
  protected def promoteToWebSocket: Route = {
    extractMatchedPath { requestUri =>
      headerValueByType(classOf[RealRemoteAddress]) { remoteAddrHdr =>
        val remoteAddress = remoteAddrHdr.address

        val (outboundMat,outbound) = Source.queue[Message](srcBufSize, srcPolicy).preMaterialize()
        val newConn = WebSocketPushConnection(outboundMat, remoteAddress)
        initializeConnection(newConn)
        connections ::= newConn

        val inbound: Sink[Message, Any] = Sink.foreach{ inMsg =>
          handleIncoming(inMsg).foreach( outMsg => pushTo(newConn,outMsg))
        }

        val flow = Flow.fromSinkAndSourceCoupled(inbound, outbound)

        info(s"promoting $requestUri to websocket connection for remote: $remoteAddress")
        handleWebSocketMessages(flow)
      }
    }
  }
}

/**
  * a websocket RaceRouteInfo that uses a request/response websocket message protocol
  *
  * Note that each response can consist of a number of messages that are sent back, but each interaction
  * starts with a client request
  */
trait ProtocolWSRaceRoute extends WSRaceRoute {

  // concrete type provides the partial function for messages that are handled
  protected val handleMessage: PartialFunction[Message,Iterable[Message]]

  // the rest is automatically consumed and ignored
  protected def discardMessage (m: Message): Iterable[Message] = {
    info(s"ignored incoming message: $m")
    m match {
      case tm: TextMessage => tm.textStream.runWith(Sink.ignore)
      case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore)
    }
    Nil
  }

  // TODO - keep alive in config causes https://github.com/akka/akka/issues/28926
  //val pingMsg: Message = BinaryMessage.Strict(ByteString.empty) // TODO only a simulated Ping frame that is not transparent

  protected val flow = Flow[Message].mapConcat { m=>
    handleMessage.applyOrElse(m, discardMessage)
  }
  // .keepAlive(1.second, () => pingMsg)  // TDDO should go into route configuration

  protected def promoteToWebSocket: Route = {
    extractMatchedPath { requestUri =>
      headerValueByType(classOf[RealRemoteAddress]) { remoteAddrHdr =>
        val remoteAddress = remoteAddrHdr.address

        info(s"promoting $requestUri to websocket connection for remote: $remoteAddress")
        handleWebSocketMessages(flow)
      }
    }
  }
}


/**
  * a route that supports web socket promotion from within a user-authenticated context
  *
  * note we only check but do not update the session token (there is no HttpResponse we could use to transmit
  * a new token value), and we provide an additional config option to specify a shorter expiration. This is based
  * on the model that the promotion request is automated (via script) from within a previous (authorized) response
  * content, i.e. the request should happen soon after we sent out that response
  *
  * note also that AuthRaceRoute does not check for session token expiration on logout requests, which in case
  * of responses that open web sockets would probably fail since those are usually long-running content used to
  * display pushed data
  */
trait AuthorizedWSRoute extends WSRaceRoute with AuthorizedRaceRoute {

  val promoWithinMillis: Long = config.getFiniteDurationOrElse("promotion-within", 10.seconds).toMillis

  protected def promoteAuthorizedToWebSocket (requiredRole: String): Route = {
    extractMatchedPath { requestUri =>
      cookie(sessionCookieName) { namedCookie =>
        userAuth.matchesSessionToken(namedCookie.value, requiredRole) match {
          case TokenMatched =>
            promoteToWebSocket

          case TokenFailure(rejection) =>
            complete(StatusCodes.Forbidden, s"invalid session token: $rejection")
        }
      } ~ complete(StatusCodes.Forbidden, "no user authorization found")
    }
  }
}

