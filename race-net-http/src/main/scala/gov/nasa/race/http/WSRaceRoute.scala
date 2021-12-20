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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive, PathMatcher, PathMatchers, Route}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, SourceQueueWithComplete}
import akka.{Done, NotUsed}
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.{Ping, Pong, RaceDataClient}
import gov.nasa.race.http.webauthn.WebAuthnMethod
import gov.nasa.race.ifSome
import gov.nasa.race.uom.Time.Seconds
import gov.nasa.race.uom.{DateTime, Time}

import java.io.File
import java.net.InetSocketAddress
import scala.collection.immutable.Iterable
import scala.collection.mutable.{Map => MutMap}
import scala.concurrent.Future
import scala.util.{Failure, Random, Success}

/**
  * root type for WebSocket RaceRouteInfos
  */
trait WSRaceRoute extends RaceRouteInfo {
  implicit val materializer: Materializer = HttpServer.materializer
  implicit val ec = HttpServer.ec // scala.concurrent.ExecutionContext.global

  protected def promoteToWebSocket(): Route
}

trait WSContext {
  def sockConn: SocketConnection
}

case class BasicWSContext (sockConn: SocketConnection) extends WSContext
case class AuthWSContext (sockConn: SocketConnection, sessionToken: String) extends WSContext

/**
  * a WSRaceRoute that can only be used by clients that have received a valid (unused) token
  * which is normally transmitted in the document that initiates the websocket request.
  * Clients transmit the token in the websocket URL: wss://<host>/<target>/ws/<token>
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
  protected def completeTokenizedWSRoute (remove: Boolean): Route = {
      headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
        val clientAddr = sockConn.remoteAddress
        extractUnmatchedPath { p =>
          var tok = p.toString()
          if (tok.startsWith("/")) tok = tok.substring(1)
          registeredTokens.get(tok) match {
            case Some(addr) =>
              // note that port will differ since this is a new request
              if (addr.getAddress == clientAddr.getAddress) {
                if (remove) registeredTokens -= tok // consume it
                info(s"accepting websocket request for registered token $tok from: $clientAddr")
                promoteToWebSocket()

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
}

/**
  * a RaceRoute that completes with a WebSocket to which messages are pushed from an
  * associated actor that received data from RACE channels and turns them into web socket Messages
  */
trait PushWSRaceRoute[T<:WSContext] extends WSRaceRoute with SourceQueueOwner[Message] with RaceDataClient {

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
    queue.offer(m).onComplete {
      case Success(_) => // all good (TODO should we check for Enqueued here?)
        info(s"pushing message $m to $remoteAddr")

      case Failure(_) =>
        info(s"dropping connection: $remoteAddr")
        connections = connections.filter( e => e._1 ne remoteAddr)
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
  def receiveData: Receive = {
    case data =>
      synchronized {
        push(TextMessage.Strict(data.toString))
      }
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
  protected def handleIncoming (ctx:T, m: Message): Iterable[Message] = {
    info(s"ignoring incoming message $m")
    discardMessage(m)
    Nil
  }

  /**
    * override in concrete routes to push initial data etc.
    * Use pushTo
    */
  protected def initializeConnection (ctx: T, queue: SourceQueueWithComplete[Message]): Unit = {
    // nothing here
  }

  /**
    * override if concrete routes need to do more cleanup
    */
  protected def handleConnectionLoss (remoteAddress: InetSocketAddress, cause: Any): Unit = {
    connections = connections.filter( e=> e._1 != remoteAddress)
    info(s"connection closed: $remoteAddress, cause: $cause")
  }

  protected def createFlow(ctx: T): Flow[Message,Message,NotUsed] = {
    val sockConn = ctx.sockConn
    val remoteAddress = sockConn.remoteAddress // this is a stable (websocket) address

    val (outboundMat,outbound) = createPreMaterializedSourceQueue
    val newConn = (remoteAddress, outboundMat)
    initializeConnection(ctx,outboundMat)
    connections = connections + newConn

    val completionFuture: Future[Done] = outboundMat.watchCompletion()
    completionFuture.onComplete { handleConnectionLoss(remoteAddress,_) } // TODO does this handle passive network failures?

    val inbound: Sink[Message, Any] = Sink.foreach{ inMsg =>
      try {
        handleIncoming( ctx, inMsg).foreach(outMsg => {
          pushTo(remoteAddress, outboundMat, outMsg)
        })
      } catch {
        case t: Throwable => warning(s"error processing incoming websocket message: $t")
      }
    }

    Flow.fromSinkAndSourceCoupled(inbound, outbound)
  }
}

trait BasicPushWSRaceRoute extends PushWSRaceRoute[BasicWSContext] {

  protected def promoteToWebSocket(): Route = {
    extractMatchedPath { requestUri =>
      headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
        val flow = createFlow(BasicWSContext(sockConn))

        info(s"promoting $requestUri to websocket connection for remote: ${sockConn.remoteAddress}")
        handleWebSocketMessages(flow)
      }
    }
  }
}

/**
  * a WSRaceRoute that periodically pings its connections and closes the ones that don't respond properly
  * note this also can be used to keep the WS alive if we own the client
  */
trait MonitoredPushWSRaceRoute[T<:WSContext] extends PushWSRaceRoute[T] {

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

/**
  * a websocket RaceRouteInfo that uses a request/response websocket message protocol which does only rely on
  * client messages and does not need to access/push from the RACE bus
  *
  * Note that each response can consist of a number of messages that are sent back, but each interaction
  * starts with a client request
  *
  * TODO - do we need an auth version of this?
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

  protected def promoteToWebSocket(): Route = {
    extractMatchedPath { requestUri =>
      headerValueByType(classOf[IncomingConnectionHeader]) { remoteAddrHdr =>
        val remoteAddress = remoteAddrHdr.remoteAddress

        info(s"promoting $requestUri to websocket connection for remote: $remoteAddress")
        handleWebSocketMessages(flow)
      }
    }
  }
}


/**
  * a route that supports web socket promotion from within a user-authorized context
  *
  * use this authorization if the websocket is embedded in some other protected content, i.e. we have a cookie-based
  * per-request authentication and the websocket request comes from an already authenticated document
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
trait AuthorizedPushWSRaceRoute extends PushWSRaceRoute[AuthWSContext] with AuthRaceRoute {

  protected def promoteToWebSocket (): Route = {
    def completeWithContext(requestUri: Uri.Path, ctx: AuthWSContext): Route = {
      val flow = createFlow(ctx)
      info(s"promoting $requestUri to websocket connection for remote: ${ctx.sockConn.remoteAddress}")

      val cookie = createSessionCookie(ctx.sessionToken)
      setCookie(cookie) {
        handleWebSocketMessages(flow)
      }
    }

    extractMatchedPath { requestUri =>
      headerValueByType(classOf[IncomingConnectionHeader]) { sockConn =>
        cookie(sessionCookieName) { namedCookie =>
          val curToken = namedCookie.value
          getNextSessionToken(requestUri, curToken) match {
            case NextToken(nextToken) => completeWithContext( requestUri, AuthWSContext(sockConn,nextToken))
            case TokenMatched => completeWithContext( requestUri, AuthWSContext(sockConn,curToken))
            case f: TokenFailure => complete(StatusCodes.Forbidden, s"invalid session token: ${f.reason}")
          }
        } ~ complete(StatusCodes.Forbidden, "no user authorization")
      }
    }
  }
}

/**
  * a route that supports authorized web socket promotion outside of other authorized content
  * This is typically the case if the client is automated
  *
  * use this authorization if the client directly requests the web socket and nothing else, i.e. we have only one
  * point of authentication, and the client uses akka-http BasicHttpCredentials (in extraHeaders)
  */
trait BasicAuthorizedWSRoute extends WSRaceRoute {

  val pwStore: PwUserStore = createPwStore

  def createPwStore: PwUserStore = {
    val fname = config.getVaultableString("user-auth")
    val file = new File(fname)
    if (file.isFile) {
      new PwUserStore(file)
    } else throw new RuntimeException("user-auth not found")
  }

  protected def promoteAuthorizedToWebSocket (requiredRole: String): Route = {
    extractCredentials { cred =>
      cred match {
        case Some(credentials) =>
          pwStore.getUserForCredentials( credentials) match {
            case Some(user) =>
              info(s"promoting to web socket for authorized user ${user.uid}")
              promoteToWebSocket()
            case None =>
              complete(StatusCodes.Forbidden, "unknown user credentials")
          }
        case None =>
          complete(StatusCodes.Forbidden, "no user credentials")
      }
    }
  }
}

/**
  * a route that can get promoted to a websocket connection which supports privileged operations, i.e. does
  * respond to certain incoming client messages by starting a authMethod based protocol
  *
  * note it is the responsibility of the concrete type to keep track of sessions
  *
  * TODO - this currently can't be an AuthRaceRoute since it masks a number of its members
  */
trait AuthorizingWSRaceRoute extends WSRaceRoute {

  val authPathMatcher = PathMatchers.separateOnSlashes(s"$requestPrefix/auth.html")
  val authSvgPathMatcher = PathMatchers.separateOnSlashes("auth.svg")  // it's a suffix matcher
  val authCssPathMatcher = PathMatchers.separateOnSlashes("auth.css")

  val authMethod: AuthMethod = createAuthMethod()
  authMethod.setLogging(info,warning,error)

  def createAuthMethod(): AuthMethod = getConfigurableOrElse[AuthMethod]("auth")(new WebAuthnMethod(NoConfig))

  def authResourceRoute: Route = {
    path(authPathMatcher) {
      headerValueByType(classOf[IncomingConnectionHeader]) { conn =>
        val remoteAddress = conn.remoteAddress
        complete(StatusCodes.OK, HttpEntity(ContentTypes.`text/html(UTF-8)`, authPage(remoteAddress)))
      }
    } ~ pathSuffix(authCssPathMatcher) {
      complete(StatusCodes.OK, HttpEntity(ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), authCSS()))
    } ~ pathSuffix(authSvgPathMatcher) {
      complete( StatusCodes.OK, HttpEntity(MediaTypes.`image/svg+xml`, authSVG()))
    }
  }

  def authPage (remoteAddress: InetSocketAddress): String = authMethod.authPage(remoteAddress)

  def authCSS(): String = authMethod.authCSS()

  def authSVG(): Array[Byte] = authMethod.authSVG()

  override def completeRoute: Route = {
    authResourceRoute ~ route
  }
}
