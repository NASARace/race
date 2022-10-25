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
package gov.nasa.race.http

import akka.actor.{ActorRef, ActorSystem, ClassicActorSystemProvider}
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{DataClientExecutor, ParentActor, ParentRaceActor, RaceContext, RaceDataClientMessage, SubscribingRaceActor}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object HttpServer {
  val asys: ActorSystem = ActorSystem("http") //context.system
  implicit val asp: ClassicActorSystemProvider = asys
  implicit val materializer: Materializer = Materializer.matFromSystem(asys)
  implicit val ec: ExecutionContext = asys.getDispatcher
}

/**
  * an actor that represents a configurable http server
  *
  * Note that content and route management is done by its configured RouteInfo objects, which might have
  * actors by themselves. This is somewhat analogous to RaceViewerActor and RaceLayer/RaceLayerActor
  *
  * This actor itself does not yet subscribe from or publish to RACE channels, but we might add control and/or
  * stats channels in the future
  */
class HttpServer (val config: Config) extends ParentRaceActor with SubscribingRaceActor with DataClientExecutor with SSLContextUser {
  import HttpServer._

  val serverTimeout = config.getFiniteDurationOrElse("server-timeout", 5.seconds)
  val wsKeepAliveInterval = config.getOptionalFiniteDuration("ws-keep-alive")
  val logIncoming = config.getBooleanOrElse("log-incoming", false)

  val routeInfos = createRouteInfos
  val route = createRoutes

  val useSSL = checkSSL(routeInfos)
  var binding: Option[Future[ServerBinding]] = None

  // override in subclasses if those get set dynmically during init
  def getInterface: String = config.getStringOrElse( "interface", "0.0.0.0") // all interfaces
  def getPort: Int = config.getIntOrElse("port", 8080)

  def createRoutes: Route = {
    val route = concat(routeInfos.map(_.completeRoute):_*)
    if (logIncoming) logRoute(route) else route
  }

  def logRoute (contentRoute: Route): Route = {
    extractRequest { request =>
      extractHost { hn =>
        info(s"${request.method.name} ${request.uri.path} from $hn")
        contentRoute
      }
    }
  }

  def getServerSettings = {
    var settings = ServerSettings(system)
    ifSome(wsKeepAliveInterval){ dur=>
      val newWsSettings = settings.websocketSettings.withPeriodicKeepAliveMaxIdle(dur)
      settings = settings.withWebsocketSettings(newWsSettings)
    }
    //... and potentially more
    settings
  }

  def createServerSource: Source[Http.IncomingConnection,Future[Http.ServerBinding]] = {
    val httpExt = Http()(system)
    val ss = getServerSettings

    val interface = getInterface
    val port = getPort

    if (useSSL) {
      info(s"server using https:// protocol on port: $port")
      getSSLContext("server-keystore") match {
        case Some(sslContext) =>
          info(s"using SSL configuration from 'server-keystore'")
          val ctx = ConnectionContext.httpsServer( () => {
            val sslEngine = sslContext.createSSLEngine()
            sslEngine.setUseClientMode(false)
            sslEngine
          })
          httpExt.newServerAt(interface,port).withSettings(ss).enableHttps(ctx).connectionSource()

        case None =>
          info("insufficient SSL configuration, falling back to default")
          httpExt.newServerAt(interface,port).withSettings(ss).connectionSource()
      }
    } else {
      info(s"server using http:// protocol on port: $port")
      httpExt.newServerAt(interface,port).withSettings(ss).connectionSource()
    }
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    routeInfos.forall( _.onRaceInitialized(this)) && super.onInitializeRaceActor(rc, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    val serverSource: Source[Http.IncomingConnection,Future[Http.ServerBinding]] = createServerSource

    val bindingFuture: Future[Http.ServerBinding] = serverSource.to(Sink.foreach { connection: Http.IncomingConnection =>
      // we add the connection data to the route-accessible HttpRequest headers so that we can
      // create the route once (during construction) and don't have to re-create it for each new connection
      val finalRoute: Route = mapRequest(req => req.addHeader(IncomingConnectionHeader(connection, useSSL))) { route }

      info(s"accepted new connection from: ${connection.remoteAddress} to: ${connection.localAddress}(${connection.localAddress.getHostName})")
      connection.handleWith( Route.toFlow(finalRoute)(system))
    }).run() //.map(_.addToCoordinatedShutdown(hardTerminationDeadline = 8.seconds))
    binding = Some(bindingFuture)


    info(s"serving requests: $requestSpec")
    routeInfos.forall( _.onRaceStarted(this)) && super.onStartRaceActor(originator)
  }

  override def handleMessage: Receive = handleDataClientMessage.orElse( super.handleMessage)

  def requestSpec: String = {
    val protocol = if (useSSL) "https" else "http"
    val rps = routeInfos.map(_.requestPrefix).mkString(",")
    s"$protocol://$getInterface:$getPort/{$rps}"
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    if (routeInfos.forall(_.onRaceTerminated(this))){
      ifSome(binding) { f =>
        info(s"$name stopped serving port: $getPort")
        f.flatMap(_.unbind())
        //Await.result(f, 9.seconds).terminate(hardDeadline = 10.seconds)
        //asys.terminate()
      }

      super.onTerminateRaceActor(originator)

    } else false  // routes did not properly terminate
  }

  def createRouteInfos: Seq[RaceRouteInfo] = {
    config.getOptionalConfigList("routes").foldLeft(Seq.empty[RaceRouteInfo]) { (seq,routeConf) =>
      val routeName = routeConf.getString("name")
      val routeClsName = routeConf.getString("class")
      info(s"creating route '$routeName': $routeClsName")
      newInstance[RaceRouteInfo](routeClsName,Array(classOf[ParentActor],classOf[Config]),Array(this,routeConf)) match {
        case Some(ri) =>
          info(s"adding route '$routeName'")
          seq :+ ri

        case None =>
          error(s"route '$routeName' did not instantiate")
          throw new RuntimeException(s"error instantiating route $routeName")
      }
    }
  }

  def checkSSL (ris: Seq[RaceRouteInfo]): Boolean = {
    config.getBooleanOrElse("use-https", ris.exists( _.shouldUseHttps))
  }
}


