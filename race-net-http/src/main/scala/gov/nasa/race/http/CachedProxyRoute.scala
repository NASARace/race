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

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.MediaType.{Binary, WithFixedCharset, WithOpenCharset}
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.{FileUtils, NetUtils}

import java.io.{File, IOException}
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.attribute.UserDefinedFileAttributeView
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
  * a RaceRoute that is a file system cached proxy
  *
  * this is a proxy that forwards to an external server which is computed or configured, i.e. provided
  * by the caller of completeCached(..serverUrl) and not deduced from the request
  */
trait CachedProxyRoute extends RaceRouteInfo {

  val maxRequestSize = config.getIntOrElse("max-request-size", 2000000)
  val maxRequestTimeout = config.getFiniteDurationOrElse("max-request-timeout", 5.seconds)

  // TODO - should be completeRelProxy and completeAbsProxy
  // and cache should be an overlay RRI

  /**
    * the main method of this trait that completes the route from filesystem content
    *
    * note that users of this method must have matched the requestUrl up to a point where the unmatched
    * path (without parameters) can be used as a filesystem pathname (under the provided fsRoot directory).
    *
    * if the content is not stored in the local filesystem it is retrieved from the provided serverUrl, which
    * is used as a prefix for the unmatched path to form the external request url
    */
  def completeCached (fsRoot: String, serverUrl: String): Route = {
    extractUri { uri=>
      extractUnmatchedPath { unmatchedPath=>
        completeCached( unmatchedPath, uri.rawQueryString, fsRoot, serverUrl)
      }
    }
  }

  def completeCached (unmatchedPath: Uri.Path, rawQuery: Option[String], fsRoot: String, serverUrl: String): Route = {
    val file = getRequestFile( fsRoot, unmatchedPath, rawQuery)
    if (file.isFile) {
      FileUtils.fileContentsAsBytes(file) match {
        case Some(data) =>
          info(s"serve from cache: $unmatchedPath")
          val contentType = getContentType(file)
          complete(HttpEntity(contentType, data))
        case None =>
          fetchData(unmatchedPath, rawQuery, serverUrl, file)
      }
    } else {
      fetchData(unmatchedPath, rawQuery, serverUrl, file)
    }
  }

  def getRequestFile (fsRoot: String, unmatchedPath: Uri.Path, rawQuery: Option[String]): File = {
    val p = NetUtils.mapToFsPathString( unmatchedPath.toString(), rawQuery)
    new File(fsRoot, p)
  }

  def fetchData (unmatchedPath : Uri.Path, rawQuery: Option[String], serverUrl: String, file: File): Route = {
    implicit val system = parent.system
    implicit val ec = scala.concurrent.ExecutionContext.global

    val reqUri = rawQuery match {
      case Some(params) => serverUrl + unmatchedPath + '?' + params
      case None => serverUrl + unmatchedPath
    }

    info(s"send request: $reqUri")
    val req: Future[HttpEntity.Strict] = Http().singleRequest( HttpRequest(uri = reqUri)).flatMap{ resp =>
      resp.entity.toStrict( maxRequestTimeout, maxRequestSize)
    }

    onComplete(req) {
      case Success(strictEntity) =>
        val data = strictEntity.getData().toArray
        info(s"cache $unmatchedPath: ${data.length} bytes")
        saveFile(file, data, strictEntity.contentType)
        complete( HttpEntity( strictEntity.contentType, data))

      case Failure(x) =>
        complete( InternalServerError, s"An error occurred: ${x.getMessage}")
    }
  }

  def saveFile (file: File, data: Array[Byte], contentType: ContentType): Unit = {
    if (!file.isFile) {
      file.getParentFile.mkdirs()
    }
    FileUtils.setFileContents(file, data)

    val path = file.toPath
    val view = Files.getFileAttributeView(path, classOf[UserDefinedFileAttributeView])
    if (view != null) {
      view.write("mimetype", Charset.defaultCharset().encode(contentType.mediaType.toString()))
    }
  }

  def getContentType (f: File): ContentType = {
    val p = f.toPath
    val view = Files.getFileAttributeView( p, classOf[UserDefinedFileAttributeView])

    if (view != null) {
      try { // Java < 17 on macOS causes exceptions if the attribute is not there
        val name = "mimetype"
        val buf = ByteBuffer.allocate(view.size(name))
        view.read(name, buf)
        buf.flip
        val value = Charset.defaultCharset.decode(buf).toString
        ContentType.parse(value) match {
          case Right(ct) => ct
          case Left(err) => guessContentType(f)
        }
      } catch {
        case x: Throwable => guessContentType(f)
      }
    } else guessContentType(f)
  }

  def guessContentType (f: File): ContentType = {
    MediaTypes.forExtension(FileUtils.getExtension(f)) match {
      case bin: Binary => bin.toContentType
      case fcs: WithFixedCharset => fcs.toContentType
      case ocs: WithOpenCharset => ocs.toContentTypeWithMissingCharset
      case other => throw new RuntimeException("unsupported content type")
    }
  }
}
