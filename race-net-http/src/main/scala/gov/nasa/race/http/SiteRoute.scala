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

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.ParentActor
import gov.nasa.race.util.FileUtils

import scala.collection.mutable

/**
  * a RaceRouteInfo that uses a cached filesystem to lookup request URIs
  * note this is a class since it can be directly used, although most cases are going to
  * subclass in order to add specific routes by overriding route() (for web sockets etc)
  *
  * Note this trait is for web sites that mostly consist of static content that is not supposed to be
  * backed by class resources and should be loaded from one location in the file system, without overriding from
  * sub-types (other than setting siteRoot).
  *
  * Use CachedFileAssetRoute if the content is overridable (either from a hierarchy of directories or classes)
  */
class SiteRoute (val parent: ParentActor, val config: Config) extends RaceRouteInfo {

  val siteRoot: File = config.getExistingDir("site-root") // excluding requestPrefix
  val cacheContent = config.getBooleanOrElse("cache", true)

  // filled on-demand
  protected val contentCache: mutable.Map[String,CachedContent] = mutable.Map.empty

  protected def textEntity (contentType: ContentType.NonBinary, file: File): Option[HttpEntity.Strict] = {
    FileUtils.fileContentsAsString(file).map(HttpEntity(contentType,_))
  }

  protected def byteEntity (contentType: ContentType, file: File): Option[HttpEntity.Strict] = {
    FileUtils.fileContentsAsBytes(file).map(HttpEntity(contentType,_))
  }

  protected def createEntity (file: File): Option[HttpEntity.Strict] = {
    MediaTypes.forExtension(FileUtils.getExtension(file)) match {
      case t:MediaType.Binary => byteEntity(t,file)
      case t:MediaType.WithOpenCharset => textEntity(ContentType(t,HttpCharsets.`UTF-8`),file)
      case t:MediaType.WithFixedCharset => textEntity(t,file)
      case _ => None
    }
  }

  protected def findExistingFile (siteDir: File, relPath: String): Option[File] = {
    var f = new File(siteDir,relPath)

    if (f.isFile) {
      Some(f)

    } else if (f.isDirectory) {
      f = new File(siteDir,relPath + "/index.html")
      if (f.isFile) Some(f) else None

    } else {
      if (FileUtils.hasExtension(f)) {
        None
      } else {
        f = new File(siteDir,relPath + ".html")
        if (f.isFile) Some(f) else None
      }
    }
  }

  // override if this is a session token checkpoint
  protected def isTokenIncrement (uri: String, file: File): Boolean = false

  protected def createContent (path: String, file: File, entity: HttpEntity.Strict): CachedContent = {
    val cc = CachedContent(siteRoot, requestPrefix, path,file,isTokenIncrement(path,file),entity)
    if (cacheContent) contentCache += path -> cc
    cc
  }

  protected def loadContent (path: String): Option[CachedContent] = {
    for (
      file <- findExistingFile(siteRoot,path);
      entity <- createEntity(file)
    ) yield {
      createContent(path,file,entity)
    }
  }

  // distinguish this from CachedFileAssetRoute.getContent
  protected def getSiteContent(path: String): Option[CachedContent] = {
    contentCache.get(path) match {
      case res@Some(cachedContent) =>
        if (cachedContent.isOutdated) loadContent(path) else res

      case None => loadContent(path)
    }
  }

  def siteRoute: Route = {
    extractUri { uri =>
      pathPrefix(requestPrefixMatcher) {
        extractUnmatchedPath { path =>
          getSiteContent(path.toString) match {
            case Some(cachedContent) =>
              cachedContent.location match {
                case Some(loc) =>
                  val location = new Location(uri.copy(path = Path(loc)))
                  respondWithHeader(location) {
                    complete( StatusCodes.SeeOther, cachedContent.entity)
                  }
                case None => complete( StatusCodes.OK, cachedContent.entity)
              }

            case None =>
              complete( StatusCodes.NotFound, s"$uri not found")
          }
        }
      }
    }
  }

  // this is only enough for static content, otherwise concrete types will override
  override def route: Route = siteRoute
}

/**
  * an access-restricted SiteRoute that is authenticated
  */
class AuthSiteRoute(parent: ParentActor, config: Config) extends SiteRoute(parent,config) with AuthRaceRoute {

  override val cookiePath: Option[String] = Some(config.getStringOrElse("cookie-path", s"/$requestPrefix"))

  // we increment on each access to an html document
  override protected def isTokenIncrement (uri: String, file: File): Boolean = FileUtils.getExtension(file) == "html"

  def getNextSessionToken (cachedContent: CachedContent, cookieValue: String): AuthTokenResult = {
    if (cachedContent.isTokenIncrement) {
      sessions.replaceExistingEntry(cookieValue)
    } else {
      sessions.matchesExistingEntry(cookieValue)
    }
  }

  override def siteRoute: Route = {
    def completeWithCachedContent (cachedContent: CachedContent, uri: Uri): Route = {
      cachedContent.location match {
        case Some(loc) =>
          respondWithHeaders(new Location(uri.copy(path = Path(loc)))) {
            completeWithCacheHeaders(StatusCodes.SeeOther, cachedContent.entity)
          }
        case None =>
          completeWithCacheHeaders(StatusCodes.OK, cachedContent.entity)
      }
    }

    extractUri { uri =>
      pathPrefix(requestPrefixMatcher) {
        extractUnmatchedPath { path =>
          val relPath = path.toString
          if (relPath.nonEmpty && relPath.last == '/') {
            complete(StatusCodes.BadRequest, s"directory access not supported: $relPath")

          } else {
            getSiteContent(relPath) match {
              case Some(cachedContent) =>
                optionalCookie(sessionCookieName) { opt =>
                  opt match {
                    case Some(namedCookie) =>
                      getNextSessionToken(cachedContent, namedCookie.value) match {
                        case NextToken(newToken) => // we have a new session cookie, set it in the header
                          setCookie(createSessionCookie(newToken)) {
                            completeWithCachedContent(cachedContent, uri)
                          }
                        case TokenMatched => completeWithCachedContent(cachedContent, uri) // no need to set new session cookie
                        case f:TokenFailure => completeWithLogin(uri) // session token was rejected, force new authentication
                      }

                    case None => completeWithLogin(uri) // no session token yet, enter authentication
                  }
                }

              case None => complete(StatusCodes.NotFound, s"$uri not found")
            }
          }
        }
      }
    }
  }
}
