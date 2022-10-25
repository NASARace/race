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

import akka.actor.Actor.Receive
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import gov.nasa.race.common.CachedByteFile
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{BusEvent, PipedRaceDataClient}
import gov.nasa.race.util.{FileUtils, NetUtils}

import java.io.File
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
  * the message type we receive from the bus.
  * Note this has to include a key (relative pathname) and the data (bytes, to stay generic)
  */
case class FileUpdate (pathName: String, data: Array[Byte])

/**
  * a PushWSRaceRoute that manages updates of a list of filehame patterns under a common root directory
  */
trait CachedFileRoute extends PushWSRaceRoute with PipedRaceDataClient {

  val routePrefix = config.getStringOrElse("route-prefix", name)
  val cacheDir = config.getExistingDir("cache-dir")
  val fileMatchers = config.getNonEmptyStringsOrElse("file-patterns", Array("**/*")).map(FileUtils.getGlobPathMatcher(cacheDir.getPath, _))

  protected var fileMap: Map[String,CachedByteFile] = initFiles

  def initFiles: Map[String,CachedByteFile] = {
    fileMatchers.foldLeft(Map.empty[String,CachedByteFile]) { (map,m) =>
      map ++ FileUtils.getMatchingFilesIn(cacheDir.getPath,m).map( f => FileUtils.relPath(cacheDir,f) -> new CachedByteFile(f))
    }
  }

  //--- route

  def cachedFileRoute: Route = {
    def completeFromMap (pathName: String): Route = {
      println("@@@ checking mapped file " + pathName)
      fileMap.get(pathName) match {
        case Some(cf) =>
          println("@@@ => cached.")
          complete(StatusCodes.OK, ResponseData.forExtension(cf.fileExtension, cf.getContent()))
        case None => complete(StatusCodes.NotFound, s"$pathName not found")
      }
    }

    get {
      extractUri { uri =>
        println(s"@@@ request for $uri")

        pathPrefix( routePrefix) {
          extractUnmatchedPath { p =>
            completeFromMap(p.toString().substring(1)) // remove leading '/'
          }

        } ~ path ("proxy") {
          parameterSeq { params =>
            val url = params.toString
            println(s"check proxy: $url")

            if (url.startsWith("http")) {
              println("@@@  from external")
              completeFromExternal(url)

            } else {
              val url = params.toString.substring(routePrefix.length + 1)
              println("@@@  from map")
              completeFromMap(url)
            }
          }
        }
      }
    }
  }

  override def route: Route = cachedFileRoute ~ super.route

  def completeFromExternal (url: String): Route = {
    implicit val system = parent.system
    implicit val ec = scala.concurrent.ExecutionContext.global

    info(s"fetch external request: $url")
    val req: Future[HttpEntity.Strict] = Http().singleRequest( HttpRequest(uri = url)).flatMap{ resp =>
      resp.entity.toStrict( 5.seconds, 2000000)
    }

    onComplete(req) {
      case Success(strictEntity) =>
        val data = strictEntity.getData().toArray
        println(s"@@@ completed external request with ${data.length} bytes of type: ${strictEntity.contentType}")
        complete( HttpEntity( strictEntity.contentType, data))

      case Failure(x) =>
        complete( InternalServerError, s"An error occurred: ${x.getMessage}")
    }
  }

  //--- WS interface

  def updateMessage (pathName: String, cf: CachedByteFile): TextMessage = {
    TextMessage.Strict(s"""{"fileUpdate":{"cat":"$routePrefix","pathName":"$pathName","date":${cf.lastModified.toEpochMillis}}}""")
  }

  // send all files we currently have
  protected override def initializeConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    fileMap.foreach { e =>
      info(s"announcing cached ${e._1}")
      pushTo( ctx.remoteAddress, queue, updateMessage(e._1, e._2))
    }
  }

  //--- DataClient interface

  override def receiveData: Receive = receiveFileUpdate.orElse(super.receiveData)

  def receiveFileUpdate: Receive = {
    case BusEvent(_, update: FileUpdate, _) => synchronized( processUpdate(update))
  }

  def processUpdate (update: FileUpdate): Unit = {
    def updateAndPush(pathName: String, cf: CachedByteFile, data: Array[Byte]): Unit = {
      cf.setContent(update.data)
      info(s"updating cached $pathName")
      push(updateMessage(pathName,cf))
    }

    val pathName = update.pathName

    fileMatchers.foreach { pm=>
      if (FileUtils.matches( pm, pathName)) {
        fileMap.get( pathName) match {
          case Some(cf) => // update
            updateAndPush( pathName, cf, update.data)
            return

          case None => // new one
            val cf = new CachedByteFile( new File(cacheDir,pathName))
            fileMap = fileMap + (pathName -> cf)
            updateAndPush( pathName, cf, update.data)
            return
        }
      }
    }

    // if we get here the update does not match our filters
    info(s"ignoring update for '$pathName'")
  }
}
