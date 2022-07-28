/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.land.actor

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, HttpRequest}
import com.typesafe.config.Config
import gov.nasa.race.common.{ActorDataAcquisitionThread, PollingDataAcquisitionThread}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.PublishingRaceActor
import gov.nasa.race.ifSome
import gov.nasa.race.land.{JpssDataReader, JpssDirReader, JpssProduct}
import gov.nasa.race.uom.Time.Milliseconds
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.FileUtils

import java.io.File
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}


/**
 * the data acquisition thread that downloads the latest S3Objects
 * note this only retrieves the data sets as files but does not parse/process the data yet
 */
class JpssDataAcquisitionThread ( val actorSystem: ActorSystem,
                                  val actorRef: ActorRef,
                                  val pollingInterval: Time,
                                  val startDate: DateTime,
                                  val maxRequestSize: Long,
                                  val maxRequestTimeout: FiniteDuration,
                                  val dataDir: File,
                                  val hdrs: Seq[HttpHeader],
                                  val products: Seq[JpssProduct]
                                 ) extends ActorDataAcquisitionThread(actorRef) with PollingDataAcquisitionThread {

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val materializer: ActorSystem = actorSystem

  override protected def poll(): Unit = {
    products.foreach( pollProduct)
  }

  def pollProduct (product: JpssProduct): Unit = {
    info(s"send request: ${product.url}")
    val pollTime = DateTime.now

    val req: Future[HttpEntity.Strict] = Http()(actorSystem).singleRequest( HttpRequest( uri = product.url, headers = hdrs)).flatMap{ resp =>
      resp.entity.toStrict( maxRequestTimeout, maxRequestSize)
    }

    req.onComplete {
      case Success(strictEntity) =>
        println(s"@@@ ----------------------------------- ${DateTime.now}")
        product.parseDir( strictEntity.getData().toArray, pollTime, startDate).foreach { dirEntry=>
          println(dirEntry)
        }

      case Failure(x) =>
        warning( s"retrieving JPSS product directory failed: ${x.getMessage}")
    }
  }
}

/**
 * actor to import data products for JPSS (Suomi NPP and J1) satellites from https://nrt3.modaps.eosdis.nasa.gov/
 */
class JpssImportActor (val config: Config) extends PublishingRaceActor {
  val interval = Milliseconds(config.getDuration("polling-interval").toMillis)
  val dataDir = FileUtils.ensureWritableDir(config.getString("data-dir")).get
  val keepFiles = config.getBooleanOrElse("keep-files", false)
  val maxRequestSize = config.getLongOrElse("max-request-size", 1024*1024*1024) // 1Gb
  val maxRequestTimeout= config.getFiniteDurationOrElse("max-request-timeout", 60.seconds)
  val hdrs = Seq(Authorization( OAuth2BearerToken(config.getVaultableString("access-token"))))

  val products = config.getConfigSeq("products").map( createJpssProduct )

  var dataAcquisitionThread: Option[JpssDataAcquisitionThread] = None // set during start

  def createJpssProduct (conf: Config): JpssProduct = {
    JpssProduct(
      conf.getString("name"),
      conf.getString("url"),
      conf.getString("satellite"),
      getConfigurable[JpssDirReader]( conf.getConfig("dir-reader")),
      getConfigurable[JpssDataReader]( conf.getConfig("data-reader"))
    )
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    val startDate: DateTime = DateTime.now - config.getFiniteDurationOrElse("history", 1.day)
    val t = new JpssDataAcquisitionThread( system,self,interval,startDate,maxRequestSize,maxRequestTimeout,dataDir,hdrs,products)
    t.setLogging(this)
    t.start()
    dataAcquisitionThread = Some(t)

    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    ifSome(dataAcquisitionThread){ _.terminate() }
    super.onTerminateRaceActor(originator)
  }
}
