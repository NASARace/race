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

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, SourceQueueWithComplete}
import scalatags.Text
import scalatags.Text.all._

import java.net.InetSocketAddress
import scala.collection.immutable.Iterable
import scala.collection.mutable.{Map => MutMap}
import scala.util.Random

sealed trait WSContext {
  def sockConn: SocketConnection
  def remoteAddress: InetSocketAddress = sockConn.remoteAddress
}

case class BasicWSContext (sockConn: SocketConnection) extends WSContext
case class AuthWSContext (sockConn: SocketConnection, sessionToken: String) extends WSContext


/**
  * root type for WebSocket RaceRouteInfos
  *
  * implementations provide a route directive that ends in a 'promoteToWebSocket()'
  */
trait WSRaceRoute extends RaceRouteInfo with CachedFileAssetRoute {
  implicit val materializer: Materializer = HttpServer.materializer
  implicit val ec = HttpServer.ec // scala.concurrent.ExecutionContext.global

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    script(src:="ws.js", tpe:="module")
  )

  // no body fragments

  override def route: Route = {
    wsRoute ~ wsAssetRoute ~ super.route
  }

  // optional route/content fragment if concrete type wants to use the ws.js handlers
  def wsAssetRoute: Route = {
    get {
      fileAssetPath("ws.js")
    }
  }

  def wsRoute: Route = {
    get {
      path(requestPrefixMatcher / "ws") {
        promoteToWebSocket()
      }
    }
  }

  protected def promoteToWebSocket(): Route = {
    extractMatchedPath { requestUri =>
      headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
        val flow = createFlow( BasicWSContext(sockConn))

        info(s"promoting $requestUri to websocket connection for remote: ${sockConn.remoteAddress}")
        handleWebSocketMessages(flow)
      }
    }
  }

  protected def createFlow (ctx: WSContext): Flow[Message,Message,NotUsed]


  /**
    * handle incoming message
    * override in subclasses if incoming messages need to be processed - default is doing nothing
    * note this executes in a synchronized context
    *
    * NOTE - since this is normally using a chain-of-responsibility for unhandled messages (explicitly delegating to
    * super) we don't need to reflect in the return value if the message was consumed
    */
  protected def handleIncoming (ctx: WSContext, m: Message): Iterable[Message] = {
    discardMessage(m)
    Nil
  }

  def discardMessage (m: Message): Unit = {
    m match {
      case tm: TextMessage => tm.textStream.runWith(Sink.ignore)
      case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore)
    }
  }

  protected def withStrictMessageData(msg: Message)(f: Array[Byte]=>Unit): Unit = {
    msg match {
      case tm: TextMessage.Strict => f(tm.text.getBytes)
      case bm: BinaryMessage.Strict => f(bm.data.toArray[Byte])
      case _ => warning("websocket message not strict")
    }
  }
}

/**
  * a WSRaceRoute that can only be used by clients that have received a valid (unused) token
  * which is normally transmitted in the document that initiates the websocket request.
  * Clients transmit the token in the websocket URL: wss://<host>/<target>/ws/<token>.
  *
  * Note this does not imply how the web socket is used
  *
  * TODO - do we need an authorized version of this? Auth would happen in the promoteToWebSocket if this is also an AuthorizingWSRaceRoute
  */
trait TokenizedWSRaceRoute extends WSRaceRoute {
  private val registeredTokens: MutMap[String,InetSocketAddress] = MutMap.empty

  /**
    * register and return a new (un-registered) token for the provided client
    */
  protected def registerTokenForClient (clientAddr: InetSocketAddress): String = {
    var tok = Random.between(0,Int.MaxValue).toString
    while (registeredTokens.contains(tok)) tok = Random.between(0,Int.MaxValue).toString
    info(s"registering websocket token $tok for client: $clientAddr")
    registeredTokens += (tok -> clientAddr)
    tok
  }

  /**
    * only promote websocket requests that have previously been registered
    */
  protected def completeTokenizedWsRoute(consumeToken: Boolean): Route = {
      headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
        val clientAddr = sockConn.remoteAddress
        extractUnmatchedPath { p =>
          var tok = p.toString()
          if (tok.startsWith("/")) tok = tok.substring(1)
          registeredTokens.get(tok) match {
            case Some(addr) =>
              // note that port will differ since this is a new request
              if (addr.getAddress == clientAddr.getAddress) {
                if (consumeToken) registeredTokens -= tok
                info(s"accepting websocket request for registered token $tok from: $clientAddr")
                promoteToWebSocket() // this is where the auth check would happen

              } else { // wrong clientAddr
                warning(s"wrong client address for registered token: $tok: $clientAddr")
                complete(StatusCodes.Forbidden, "invalid websocket request")
              }
            case None =>
              warning(s"un-registered request from client: $clientAddr")
              complete(StatusCodes.Forbidden, "invalid websocket request")
          }
        }
      }
  }

  override def wsRoute: Route = {
    get {
      pathPrefix(requestPrefixMatcher / "ws") { // [AUTH]
        completeTokenizedWsRoute(true)
      }
    }
  }
}

/**
  * a WSRaceRoute that uses a request/response websocket message protocol, i.e. it only reacts to client messages
  * and does not push own data
  */
trait ProtocolWSRaceRoute extends WSRaceRoute {

  // TODO - keep alive in config causes https://github.com/akka/akka/issues/28926
  //val pingMsg: Message = BinaryMessage.Strict(ByteString.empty) // TODO only a simulated Ping frame that is not transparent

  //protected val flow = Flow[Message].mapConcat( handleIncoming)
  protected def createFlow(ctx: WSContext): Flow[Message,Message,NotUsed] = {
    Flow[Message].mapConcat { msg=>
      handleIncoming(ctx,msg)
    }
  }

  // .keepAlive(1.second, () => pingMsg)  // TDDO should go into route configuration
}


