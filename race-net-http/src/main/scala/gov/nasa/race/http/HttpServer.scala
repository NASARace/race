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

import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.actor.ActorRef
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ParentContext, ParentRaceActor, RaceInitializeException}
import gov.nasa.race.util.{CryptUtils, FileUtils}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
  * an actor that represents a configurable http server
  *
  * Note that content and route management is done by its configured RouteInfo objects, which might have
  * actors by themselves. This is somewhat analogous to RaceViewerActor and RaceLayer/RaceLayerActor
  *
  * This actor itself does not yet subscribe from or publish to RACE channels, but we might add control and/or
  * stats channels in the future
  */
class HttpServer (val config: Config) extends ParentRaceActor {
  implicit val materializer = ActorMaterializer()

  val host = config.getStringOrElse("host", "localhost")
  val port = config.getIntOrElse("port", 8080)
  val serverTimeout = config.getFiniteDurationOrElse("server-timeout", 5.seconds)

  val httpExt = Http()(system)
  val connectionContext: ConnectionContext = getConnectionContext

  val routeInfos = createRouteInfos

  val route = createRoutes
  var binding: Option[Future[ServerBinding]] = None

  def createRoutes = {
    val route = concat(routeInfos.map(_.internalRoute):_*)
    if (config.getBooleanOrElse("log-incoming", false)) logRoute(route) else route
  }

  def logRoute (contentRoute: Route): Route = {
    extractRequest { request =>
      extractHost { hn =>
        info(s"${request.method.name} ${request.uri.path} from $hn")
        contentRoute
      }
    }
  }

  def getConnectionContext: ConnectionContext = {
    if (config.getBooleanOrElse("use-https", false)) {
      ConnectionContext.https(getSSLContext)
    } else {
      httpExt.defaultServerHttpContext
    }
  }

  def getSSLContext: SSLContext = {
    val ksPathName = config.getVaultableStringOrElse("server-keystore", "server.ks")
    FileUtils.existingNonEmptyFile(ksPathName) match {
      case Some(ksFile) =>
        val pw: Array[Char] = config.getVaultableString("server-keystore-pw").toCharArray

        val ksType = config.getStringOrElse("server-keystore-type", CryptUtils.keyStoreType(ksPathName))
        val ks: KeyStore = KeyStore.getInstance(ksType)
        ks.load(new FileInputStream(ksFile),pw)

        val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
        keyManagerFactory.init(ks, pw)
        CryptUtils.erase(pw, ' ')

        val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
        tmf.init(ks)

        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

        sslContext

      case None =>
        throw new ConfigException.Generic("invalid server keystore")
    }
  }

  override def onStartRaceActor(originator: ActorRef) = {
    binding = Some(httpExt.bindAndHandle(route, host, port, connectionContext))
    info(s"$name serving on http://$host:$port")
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    ifSome(binding) { f =>
      f.flatMap(_.unbind())
      Await.ready(f, serverTimeout)
    }
    info(s"$name stopped serving")
    super.onTerminateRaceActor(originator)
  }

  def createRouteInfos: Seq[RaceRouteInfo] = {
    config.getOptionalConfigList("routes").foldLeft(Seq.empty[RaceRouteInfo]) { (seq,routeConf) =>
      val routeName = routeConf.getString("name")
      val routeClsName = routeConf.getString("class")
      info(s"creating route '$routeName': $routeClsName")
      newInstance[RaceRouteInfo](routeClsName,Array(classOf[ParentContext],classOf[Config]),Array(this,routeConf)) match {
        case Some(ri) =>
          info(s"adding route '$routeName'")
          if (ri.shouldUseHttps && !connectionContext.isSecure){
            warning(s"use of AuthorizedRaceRoute '$routeName' without secure connectionContext (set 'use-https=true')")
          }
          seq :+ ri

        case None =>
          error(s"route '$routeName' did not instantiate")
          throw new RuntimeException(s"error instantiating route $routeName")
      }
    }
  }
}


