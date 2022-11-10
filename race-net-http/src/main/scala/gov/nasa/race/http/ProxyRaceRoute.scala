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

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.MediaType.{Binary, WithFixedCharset, WithOpenCharset}
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpRequest, HttpResponse, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.server.Directives.{complete, extractUnmatchedPath, extractUri, onComplete, parameterSeq}
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.model.headers.Expires
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings, ParserSettings}
import gov.nasa.race.common.LongStringDigest
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.util.{FileUtils, NetUtils}
import gov.nasa.race.uom.DateTime

import java.io.File
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.attribute.UserDefinedFileAttributeView
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}


/**
  * a RaceRouteInfo that gets the content from external servers
  *
  * note that this is a root type which does not use any caching
  * NOTE future callbacks should NOT access/modify actor state as they are executed in a non-actor thread
  * (use pipeTo(self) => handle HttpResponse or RaceActor.execInActorThread(f))
  */
trait ProxyRaceRoute extends RaceRouteInfo {

  val maxRequestSize = config.getIntOrElse("max-request-size", 20000000)
  val maxRequestTimeout = config.getFiniteDurationOrElse("max-request-timeout", 5.seconds)

  /**
    * the main method of this trait that completes the route by forwarding the request to an external server
    *
    * note that callers of this method must have matched the requestUrl up to a point where the unmatched
    * path (without parameters) can be used to construct the external request URL within this route
    */
  def completeProxied (serverUrl: String): Route = {
    extractUri { uri=>
      extractUnmatchedPath { unmatchedPath=>
        val reqUri = proxiedRequestUri( unmatchedPath, uri.rawQueryString, serverUrl)
        fetchData( unmatchedPath, reqUri)
      }
    }
  }

  protected def fetchData (unmatchedPath: Uri.Path, reqUri: String): Route = {
    onComplete(runServerRequest(reqUri)) {
      case Success((strictEntity,resp)) =>
        completeFetchSuccess(reqUri, strictEntity, resp)

      case Failure(x) =>
        info(s"proxied request for $reqUri failed with '$x'")
        complete( StatusCodes.BadRequest, s"resource not available: ${unmatchedPath}")
    }
  }

  def runServerRequest (reqUri: String): Future[(HttpEntity.Strict,HttpResponse)] = {
    implicit val system = parent.system
    implicit val ec = scala.concurrent.ExecutionContext.global

    info(s"sending proxied request: $reqUri")
    Http().singleRequest(HttpRequest(uri = reqUri)).flatMap { resp =>
      resp.entity.toStrict(maxRequestTimeout, maxRequestSize).map(strictEntity => (strictEntity, resp))
    }
  }

  // override if we have to translate the unmatched uri to get the proxied request (instead of just concatenating)
  protected def proxiedRequestUri (unmatchedPath : Uri.Path, rawQuery: Option[String], serverUrl: String): String = {
    rawQuery match {
      case Some(params) => serverUrl + unmatchedPath + '?' + params
      case None => NetUtils.concatenatePaths(serverUrl,unmatchedPath.toString())
    }
  }

  // override if we have to cache/store
  protected def completeFetchSuccess (reqUri: String, strictEntity: HttpEntity.Strict, response: HttpResponse): Route = {
    val data = strictEntity.getData().toArray
    info(s"completed proxied request $reqUri: ${data.length} bytes received")
    complete( HttpEntity( strictEntity.contentType, data))
  }
}

/**
  * a ProxyRaceRoute with a configured default host, i.e. caller of 'completeProxied' does not have to provide
  * an explicit host to forward the request to, but it has to match the path up to where the still unmatched part
  * is sufficient to construct the external request uri
  *
  * note this route does not mask explicit use of completeProxied(host), i.e. the caller can still provide an
  * alternative server
  */
trait ConfigProxyRoute extends ProxyRaceRoute {
  val defaultServer = config.getString("default-server")

  def completeProxied: Route = completeProxied(defaultServer)
}

/**
  * a ProxyRaceRoute in which the external request (incl. scheme, host, port, path and query) is provided as URL query
  * part of the matched path
  * This can be used by clients that are depending on proxied requests because the external servers don't provide
  * CORS headers
  */
trait QueryProxyRoute extends ProxyRaceRoute {

  def completeProxied: Route = {
    parameterSeq { params =>  // this does the url decoding
      val reqUri = params.toString
      reqUri match {
        case NetUtils.UrlRE(scheme,usr,host,port,path,query) =>
          if (host.nonEmpty && path.nonEmpty) {
            info(s"complete proxied from query: $reqUri")
            extractUnmatchedPath { unmatchedPath=>
              fetchData( unmatchedPath, reqUri)
            }

          } else complete(StatusCodes.BadRequest, "malformed proxy request")
        case _ => complete(StatusCodes.BadRequest, "malformed proxy request")
      }
    }
  }
}

object FSCachedProxyRoute {
  val FATTR_MIME_TYPE = "mimetype"
  val FATTR_EXPIRES = "expires"

  val MaxFileNameLength = 128
}

/**
  * a ProxyRaceRoute that uses the local filesystem as cache, storing content type and optional expiration
  * as file attributes
  *
  * the file name is computed from the unmatched request path
  */
trait FSCachedProxyRoute extends ProxyRaceRoute {
  import FSCachedProxyRoute._

  val cacheDir: File = new File(config.getStringOrElse("cache-dir", ".cache"))
  val md = new LongStringDigest(MaxFileNameLength)

  if (FileUtils.ensureWritableDir(cacheDir).isEmpty) throw new RuntimeException(s"invalid cache dir: $cacheDir")

  def fetchFile(file: File, reqUri: String)(saveAction: =>Unit): Unit = {
    implicit val ec = scala.concurrent.ExecutionContext.global

    runServerRequest(reqUri).onComplete {
      case Success((strictEntity,response)) =>
        val data = strictEntity.getData().toArray
        saveFile(file, data, strictEntity.contentType, response.header[Expires])
        saveAction

      case Failure(x) =>
        info(s"fetching file for proxied  $reqUri failed with '$x'")
    }
  }

  // check filesystem before we reach out
  // FIXME - too much redundancy with ProxyRaceRoute
  override protected def fetchData (unmatchedPath : Uri.Path, reqUri: String): Route = {

    def completeWithServerRequest (file: File, reqUri: String): Route = {
      onComplete( runServerRequest(reqUri)) {
        case Success((strictEntity,response)) =>
          val data = strictEntity.getData().toArray
          saveFile(file, data, strictEntity.contentType, None /* response.header[Expires] */)
          complete( HttpEntity( strictEntity.contentType, data))

        case Failure(x) =>
          info(s"proxied request for $reqUri failed with '$x'")
          complete( StatusCodes.BadRequest, s"resource not available: ${unmatchedPath}")
      }
    }

    getFileFromRequestUri(reqUri) match {
      case Some(file) =>
        if (file.isFile) {
          FileUtils.fileContentsAsBytes(file) match {
            case Some(data) =>
              val contentType = getContentType(file)
              val expires = getExpiration(file)

              if (DateTime.now < expires) {
                info(s"serve from cache: $unmatchedPath")
                complete(HttpEntity(contentType, data))
              } else {
                completeWithServerRequest(file,reqUri) // expired
              }

            case None => // file is empty
              completeWithServerRequest(file,reqUri)
          }
        } else {  // file does not exist yet
          completeWithServerRequest(file,reqUri)
        }

      case None => super.fetchData(unmatchedPath,reqUri) // we can't store this request as file
    }
  }

  def getFileFromRequestUri(reqUri: String): Option[File] = {
    NetUtils.decodeUri(reqUri) match {
      case NetUtils.UrlRE(_,_,host,_,path,query) =>
        val p = NetUtils.mapToFsPathString( host, path, query)
        val file = new File(cacheDir, p)
        val fname = file.getName
        if (fname.length <= MaxFileNameLength) {
          Some(file)
        } else {
          val hashedName = md.hash(fname)
          Some( new File(file.getParent, hashedName))
        }

      case _ => None
    }
  }

  def saveFile (file: File, data: Array[Byte], contentType: ContentType, expires: Option[Expires]): Unit = {
    if (!file.isFile) {
      file.getParentFile.mkdirs()
    }
    FileUtils.setFileContents(file, data)

    val path = file.toPath
    val view = Files.getFileAttributeView(path, classOf[UserDefinedFileAttributeView])
    if (view != null) {
      // some filesystems have subtle constraints we don't want to turn into internal server errors (500)
      // (e.g. 'file name too long' for macOS)
      try {
        val mimeType = Charset.defaultCharset().encode(contentType.mediaType.toString())
        view.write(FATTR_MIME_TYPE, mimeType)
        expires.foreach { e =>
          view.write(FATTR_EXPIRES, Charset.defaultCharset().encode(e.date.toString()))
        }
      } catch {
            // FIXME - on macOS this runs into 'File name too long' FileSystemExceptions if this is a long (e.g. WMS) URL
        case x: Throwable => info(s"could not store extended fileattr for $file: $x")
      }
    }
  }

  def getContentType (f: File): ContentType = {
    FileUtils.getTextFileAttribute(f, FATTR_MIME_TYPE) match {
      case Some(value) =>
        ContentType.parse(value) match {
          case Right(ct) => ct
          case Left(err) =>
            warning(s"error parsing content type: $err")
            guessContentType(f)
        }
      case None => guessContentType(f)
    }
  }

  def guessContentType (f: File): ContentType = {
    MediaTypes.forExtension(FileUtils.getExtension(f)) match {
      case bin: Binary => bin.toContentType
      case fcs: WithFixedCharset => fcs.toContentType
      case ocs: WithOpenCharset => ocs.toContentTypeWithMissingCharset
      case other => throw new RuntimeException("unsupported content type")
    }
  }

  def getExpiration (f: File): DateTime = {
    FileUtils.getTextFileAttribute(f, FATTR_EXPIRES) match {
      case Some(value) =>
        try {
          DateTime.parseISO(value)
        } catch {
          case t: Throwable => DateTime.NeverDateTime // TODO - should we re-fetch this?
        }
      case None => DateTime.NeverDateTime
    }
  }

  def getFileDate (f: File): DateTime = {
    if (f.isFile) {
      DateTime.ofEpochMillis(f.lastModified())
    } else {
      DateTime.UndefinedDateTime
    }
  }
}

