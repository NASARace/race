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
package gov.nasa.race.http

import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, ContinuousTimeRaceActor, PublishingRaceActor, RaceActor, SubscribingRaceActor}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, RequestEntity}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.{BufferOverflowException, Materializer}
import com.typesafe.config.Config

import java.io.{File, FileOutputStream}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * an actor that uses Akka-http
 *
 * used to factor out common akka-http objects and methods
 */
trait HttpActor extends RaceActor {

  val maxRequestSize = config.getIntOrElse("max-request-size", 5*1024*1000)
  val maxRequestTimeout = config.getFiniteDurationOrElse("max-request-timeout", 15.seconds)
  val maxConnectingTimeout = config.getFiniteDurationOrElse("max-connecting-timeout", 15.seconds)

  val retryDelay = config.getFiniteDurationOrElse("retry-delay", 2.seconds)
  val maxRetry = config.getIntOrElse("max-retry", 3)

  val clientSettings = {
    val origSettings = ClientConnectionSettings(system.settings.config)
    ConnectionPoolSettings(system.settings.config).withConnectionSettings( origSettings.withConnectingTimeout(maxConnectingTimeout))
  }

  // we might get ambiguities if those are public or protected
  private implicit val materializer: Materializer = Materializer.matFromSystem(context.system)
  private implicit val executionContext = scala.concurrent.ExecutionContext.global
  private val http = Http(context.system)

  def httpRequest [U] (req: HttpRequest)(f: Try[HttpResponse]=>U): Unit = {
    http.singleRequest(req, settings = clientSettings).onComplete {
      case v@Success(resp) =>
        f(v)
        resp.discardEntityBytes()
      case x@Failure(_) => f(x)
    }

    //http.singleRequest(req, settings = clientSettings).onComplete(f)
    //http.singleRequest(req, settings = clientSettings).andThen { case res => f(res) }.andThen { case res => res.get.discardEntityBytes() }
  }

  def httpRequestWithRetry [U] (req: HttpRequest, retries: Int)(f: Try[HttpResponse]=>U): Unit = {
    httpRequest(req){
      case Failure(box: BufferOverflowException) =>
        if (retries <= 0) throw box // rethrow, let caller handle it
        warning(s"request overload of ${req.uri}, remaining retries: $retries")
        scheduleOnce(retryDelay)(httpRequestWithRetry(req, retries-1)(f))
      case v => f(v)
    }
  }

  def httpRequest [U] (uri: String,
                       method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty
                      )(f: Try[HttpResponse]=>U): Unit = {
    httpRequest( HttpRequest( method, uri, headers, entity))(f)
  }

  // TODO - add timeout and size bounds
  def httpRequestFile (uri: String, file: File,
                         method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty
                        ): Future[File] = {
    val req = HttpRequest( method, uri, headers, entity)
    http.singleRequest(req, settings = clientSettings).flatMap( resp=> {
      val stream = resp.entity.dataBytes
      stream.runFold(new FileOutputStream(file))( (fos,data) => {
        fos.write(data.toArray)
        fos
      }).map( fos=> { fos.close(); file})
    })
  }

  def httpRequestWithRetry [U] (uri: String,
                                method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty,
                                retries: Int = maxRetry
                               )(f: Try[HttpResponse]=>U): Unit = {
    httpRequestWithRetry( HttpRequest( method, uri, headers, entity), retries)(f)
  }

  def httpRequestStrict [U] (req: HttpRequest)(f: Try[HttpEntity.Strict]=>U): Unit = {
    http.singleRequest(req).flatMap(_.entity.toStrict(maxRequestTimeout, maxRequestSize)).onComplete(f)
  }

  // retry after delay if we get a pending request buffer overflow
  def httpRequestStrictWithRetry [U](req: HttpRequest, retries: Int)(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrict(req){
      case Failure(box: BufferOverflowException) =>
        if (retries <= 0) throw box // rethrow, let caller handle it
        warning(s"request overload of ${req.uri}, remaining retries: $retries")
        scheduleOnce(retryDelay)(httpRequestStrictWithRetry(req, retries-1)(f))
      case v => f(v)
    }
  }

  def httpRequestStrict [U] (uri: String,
                             method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty
                            )(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrict( HttpRequest( method, uri, headers, entity))(f)
  }

  def httpRequestStrictWithRetry [U] (uri: String,
                                      method: HttpMethod = HttpMethods.GET, headers: Seq[HttpHeader] = Nil, entity: RequestEntity = HttpEntity.Empty,
                                      retries: Int=maxRetry
                                     )(f: Try[HttpEntity.Strict]=>U): Unit = {
    httpRequestStrictWithRetry( HttpRequest( method, uri, headers, entity), retries)(f)
  }

  def toStrictEntity [U] (resp: HttpResponse)(f: Try[HttpEntity.Strict]=>U): Unit = {
    resp.entity.toStrict( maxRequestTimeout, maxRequestSize).onComplete(f)
  }

  def awaitHttpRequest [U] (req: HttpRequest)(f: PartialFunction[Try[HttpResponse],U]): Unit = {
    Await.ready( http.singleRequest(req, settings = clientSettings).andThen(f), maxRequestTimeout)
  }
}


/**
 * file request message objects
 */
case class RequestFile (url: String, file: File, publishResult: Boolean = false)
case class FileRetrieved (req: RequestFile, date: DateTime)
case class FileRequestFailed (req: RequestFile, reason: String)

/**
 * an HttpActor that listens on a channel for RequestFile messages and responds to the requester either with a FileRetrieved
 * message once the download is complete or a FileRequestFailed message in case there is an error
 */
trait HttpFileRetriever extends HttpActor with SubscribingRaceActor with PublishingRaceActor with ContinuousTimeRaceActor {

  def handleFileRequestMessage: Receive = {
    case req:RequestFile => requestFile(req, sender())
    case BusEvent(_,req:RequestFile,requester) => requestFile(req, requester)
  }

  override def handleMessage: Receive = handleFileRequestMessage orElse super.handleMessage

  def requestFile (req: RequestFile, requester: ActorRef): Unit = {
    val url = req.url
    val file = req.file

    if (FileUtils.ensureWritable(file).isDefined) {
      info(s"requesting $url -> $file")
      httpRequestFile(url,file).onComplete {
        case Success(f) =>
          info(s"download complete: $f")
          val msg = FileRetrieved( req, currentSimTime)
          if (req.publishResult) publish( msg) else requester ! msg

        case Failure(x) =>
          warning(s"download failed: $x")
          requester ! FileRequestFailed( req, x.getMessage)
      }
    } else {
      requester ! FileRequestFailed(req, "file not writable")
    }
  }
}

/**
 * simple test actor
 */
class SimpleFileRetriever (val config: Config) extends HttpFileRetriever {
  val url = config.getString("url")
  val file = new File(config.getString("file"))

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator)

    self ! RequestFile(url,file, true)
    true
  }
}