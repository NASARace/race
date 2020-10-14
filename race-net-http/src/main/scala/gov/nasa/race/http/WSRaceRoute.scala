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

import java.io.File
import java.net.InetSocketAddress

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, SourceQueueWithComplete}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceDataClient
import gov.nasa.race.ifSome

import scala.collection.immutable.Iterable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * root type for WebSocket RaceRouteInfos
  */
trait WSRaceRoute extends RaceRouteInfo {
  implicit val materializer: Materializer = HttpServer.materializer
  implicit val ec = HttpServer.ec // scala.concurrent.ExecutionContext.global

  protected def promoteToWebSocket: Route
}

/**
  * a RaceRoute that completes with a WebSocket to which messages are pushed from an
  * associated actor that received data from RACE channels and turns them into web socket Messages
  */
trait PushWSRaceRoute extends WSRaceRoute with SourceQueueOwner with RaceDataClient {

  protected var connections: Map[InetSocketAddress,SourceQueueWithComplete[Message]] = Map.empty

  def hasConnections: Boolean = connections.nonEmpty

  protected def push (m: Message): Unit = synchronized {
    connections.foreach (e => pushTo(e._1,e._2, m))
  }

  protected def pushTo (remoteAddr: InetSocketAddress, m: Message): Unit = synchronized {
    ifSome(connections.get(remoteAddr)) { queue=>
      pushTo(remoteAddr,queue,m)
    }
  }

  protected def pushTo(remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message], m: Message): Unit = synchronized {
    queue.offer(m).onComplete { res=>
      res match {
        case Success(_) => // all good (TODO should we check for Enqueued here?)
          info(s"pushing message $m to $remoteAddr")

        case Failure(_) =>
          info(s"dropping connection: $remoteAddr")
          connections = connections.filter( e => e._1 ne remoteAddr)
      }
    }
  }

  protected def pushToFiltered (m: Message)( f: InetSocketAddress=>Boolean): Unit = synchronized {
    connections.foreach { e=>
      if (f(e._1)) pushTo(e._1, e._2,m)
    }
  }

  /**
    * called by associated actor
    * NOTE - this is executed from the actor thread and can modify connections so we have to synchronize
    */
  def receiveData(data: Any): Unit = {
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
  protected def handleIncoming (remoteAddr: InetSocketAddress, m: Message): Iterable[Message] = {
    info(s"ignoring incoming message $m")
    discardMessage(m)
    Nil
  }

  /**
    * override in concrete routes to push initial data etc.
    * Use pushTo
    */
  protected def initializeConnection (remoteAddr: InetSocketAddress, queue: SourceQueueWithComplete[Message]): Unit = {
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

        val (outboundMat,outbound) = createPreMaterializedSourceQueue
        val newConn = (remoteAddress, outboundMat)
        initializeConnection(remoteAddress,outboundMat)
        connections = connections + newConn

        val inbound: Sink[Message, Any] = Sink.foreach{ inMsg =>
          handleIncoming(remoteAddress, inMsg).foreach( outMsg => pushTo(remoteAddress, outboundMat,outMsg))
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
  * use this authorization if the websocket is embedded in some other protected content, i.e. we have a cookie-based
  * per-request authentication.
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

/**
  * a route that supports authorized web socket promotion outside of other authorized content
  *
  * use this authorization if the client directly requests the web socket and nothing else, i.e. we have only one
  * point of authentication, and the client uses akka-http BasicHttpCredentials (in extraHeaders)
  */
trait BasicAuthorizedWSRoute extends WSRaceRoute {

  val pwStore: PasswordStore = createPwStore

  def createPwStore: PasswordStore = {
    val fname = config.getVaultableString("user-auth")
    val file = new File(fname)
    if (file.isFile) {
      new PasswordStore(file)
    } else throw new RuntimeException("user-auth not found")
  }

  protected def promoteAuthorizedToWebSocket (requiredRole: String): Route = {
    extractCredentials { cred =>
      cred match {
        case Some(credentials) =>
          pwStore.verifyBasic(credentials.token) match {
            case Some(user) =>
              info(s"promoting to web socket for authorized user ${user.uid}")
              promoteToWebSocket
            case None =>
              complete(StatusCodes.Forbidden, "unknown user credentials")
          }
        case None =>
          complete(StatusCodes.Forbidden, "no user credentials")
      }
    }
  }
}