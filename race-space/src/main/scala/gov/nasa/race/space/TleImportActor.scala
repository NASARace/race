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
package gov.nasa.race.space

import akka.actor.ActorRef
import akka.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Set-Cookie`
import akka.util.ByteString
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, RaceContext, SubscribingRaceActor}
import gov.nasa.race.http.{HttpActor, SetCookie}
import gov.nasa.race.uom.DateTime.UndefinedDateTime
import gov.nasa.race.uom.Time.UndefinedTime
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{ifSome, ifTrue, yieldInitialized}

import java.io.{File, FileInputStream, FileOutputStream, PrintStream}
import scala.collection.immutable.Queue
import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, Future, TimeoutException}
import scala.io.Source
import scala.util.{Failure, Success}

/**
 * event object to request a TLE
 * If maxAge is specified and exceeds the age of a cached value the TleImportActor should first try to obtain
 * a newer TLE. Note there is no guarantee that there is one on space-track.org
 */
case class TleRequest (requester: ActorRef, satId: Int, maxAge: Time = UndefinedTime, tleDate: DateTime = UndefinedDateTime) {
  def this (requester: ActorRef, conf: Config) = this(requester,conf.getInt("sat"),conf.getOptionalDurationTime("max-age"))
  override def toString: String = s"TleRequest(${requester.path.name},$satId,$maxAge,$tleDate)"
}

/**
 * actor that imports and stores TLEs from space-track.org
 *
 * this actor listens on a request channel, checks if it has a current TLE for the requested satellite, queries the
 * TLE from space-track.org if not, and directly replies to the requester
 *
 * data requests to space-track.org require a session cookie that is obtained during login. That cookie currently
 * expires after 2h, which requires re-login as data responses do not update that cookie. This would enable us to
 * have overlapping requests but we opt for an internal queue to sequentialize requests so that we could also handle
 * cookie updates. Both request number and change frequency of TLEs do not require concurrent queries
 *
 * NOTE - HttpRequests are handled with futures. Make sure the future callbacks (which are executed in akka-http threads)
 * do not introduce race conditions by delegating back into the actor thread
 */
class TleImportActor (val config: Config) extends SubscribingRaceActor with HttpActor {
  type SatId = Int

  //--- internal messages to transfer processing from future callbacks into the actor thread
  case class LoginResponse (resp: HttpResponse)
  case class DataResponse (req: TleRequest, resp: HttpResponse, tle: TLE)

  protected var tleEntries: Map[SatId,TLE] = Map.empty  // where we keep the known TLEs (with respective clients)
  protected var requestQueue: Queue[TleRequest] = Queue.from(config.getConfigSeq("preload").map( new TleRequest(self,_)))

  val dataUrl: String = config.getStringOrElse("data-url", "https://www.space-track.org/basicspacedata/query/class/gp/NORAD_CAT_ID/$SAT_ID/orderby/TLE_LINE1%20ASC/format/3le")
  val historyDataUrl: String = config.getStringOrElse("history-data-url", "https://www.space-track.org/basicspacedata/query/class/gp_history/NORAD_CAT_ID/$SAT_ID/orderby/TLE_LINE1%20ASC/EPOCH/$BEGIN_DATE--$END_DATE/format/3le")

  private var authCookie: Option[SetCookie] = None // set after successful login
  private val loginRequest = getLoginRequest // we might have to repeatedly login

  val dataDir = config.getOptionalString("data-dir").flatMap( FileUtils.ensureDir) // if not set data will not be stored in files

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    readSavedTLEs()
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    ifTrue (super.onStartRaceActor(originator)) {
      startLogin() // we can't do anything before we are logged in and have an authCookie
    }
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    try {
      saveTLEs()
      awaitHttpRequest(getLogoutRequest) {
        case Success(_) => // nothing
        case Failure(x) => warning(s"failed to logout on space-track.org: $x")
      }
      info("logout on space-track.org complete")
    } catch {
      case x: TimeoutException => warning("logout request on space-track.org timed out")
    }
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case LoginResponse(resp) => processLoginResponse( resp)
    case DataResponse(req,resp,tle) => processDataResponse( req,resp,tle)
    case BusEvent(_,tleRequest: TleRequest,_) => processTleRequest(tleRequest)
  }

  //--- the HttpRequest senders - NOTE that future callbacks are executed in another thread

  def startLogin(): Unit = {
    info("starting login to space-track.org")
    httpRequest(loginRequest) { // WATCH OUT - executed async (not in actor thread)
      case Success(resp: HttpResponse) =>
        self ! LoginResponse(resp) // get back into actor thread to process response

      case Failure(x) =>
        val msg = s"failed to login to space-track.org: $x"
        if (isOptional) {
          warning(msg)
        } else {
          error(msg)
          requestTermination  // note this is not synchronous and might come after successful start of other actors
        }
    }
  }

  def processLoginResponse(resp: HttpResponse): Unit = {
    authCookie = getSetCookie(resp)
    info(s"processing login response: $authCookie")
    processNextRequest()
  }

  protected def processNextRequest(): Unit = {
    while (requestQueue.nonEmpty) {
      val req = requestQueue.head
      requestQueue = requestQueue.tail

      if (req.tleDate.isUndefined) {
        processCurrentRequest(req)
      } else {
        processHistoryRequest(req)
      }
    }
  }

  def processCurrentRequest(req: TleRequest): Unit = {
    tleEntries.get(req.satId) match {
      case Some(tle) => // we already have this tle
        if (req.requester != self) {
          if (req.maxAge.isDefined) {
            if (DateTime.now.timeSince(tle.date) <= req.maxAge) { // cached value recent enough
              info(s"responding with up-to-date cached value to $req")
              req.requester ! tle

            } else { // cached value too old, try to get a newer one
              info(s"trying to update cached value for $req")
              startDataRequest( req)
              return
            }
          } else { // requester did not ask for maxAge - return what we've got
            info(s"responding with cached value to $req")
            req.requester ! tle
          }
        } else {
          startDataRequest(req)
          return
        }

      case None => // we don't have the requested TLE yet
        info(s"sending new request for $req")
        startDataRequest(req)
        return
    }
  }

  // we don't store these so there is no need to loop back into the actor thread
  // Unfortunately we can only request ranges, not a specific date - reply with the last TLE
  def processHistoryRequest(req: TleRequest): Unit = {
    httpRequest( getHistoryDataRequest(req)) {
      case Success(resp: HttpResponse) =>
        toStrictEntity(resp) {
          case Success(httpEntity) =>
            val lines = Source.fromBytes(httpEntity.data.toArray[Byte]).getLines().toArray
            // this is most likely a list of TLEs - take the last 3 lines
            val len = lines.length
            if (len % 3 == 0 && len >= 3) {
              val l0 = lines(len-3)
              val l1 = lines(len-2)
              val l2 = lines(len-1)
              req.requester ! TLE( Some(l0), l1, l2)

            } else warning(s"ignore invalid historic 3LE data format for ${req.satId} at ${req.tleDate}")
          case Failure(x) => warning(s"failed to retrieve historic data for ${req.satId}")
        }
      case Failure(x) => warning(s"failed to obtain history TLE for ${req.satId} at ${req.tleDate}")
    }
  }

  def startDataRequest (req: TleRequest): Unit = {
    info(s"sending data request to space-track.org: $req")
    httpRequest(getDataRequest(req)) {  // WATCH OUT - executed async (not in actor thread)
      case Success(resp: HttpResponse) =>
        toStrictEntity( resp) {
          case Success(httpEntity) =>
            val lines = Source.fromBytes(httpEntity.data.toArray[Byte]).getLines().toArray
            if (lines.length == 3) {
              self ! DataResponse(req, resp, TLE( Some(lines(0)), lines(1), lines(2))) // get back into the actor thread

            } else {
              warning(s"ignore invalid 3LE data format for ${req.satId}")
              lines.foreach(println)
            }
          case Failure(x) => warning(s"failed to retrieve data for ${req.satId}")
        }
      case Failure(x) => warning(s"failed to obtain TLE for ${req.satId}")
    }
  }

  def processDataResponse (req: TleRequest, resp: HttpResponse, newTle: TLE): Unit = {
    val newCookie = getSetCookie(resp)
    if (newCookie.isDefined) authCookie = newCookie
    info(s"processing data response for: $req")

    tleEntries = tleEntries + (newTle.catNum -> newTle)
    if (req.requester != self) req.requester ! newTle

    processNextRequest() // process next request (if any)
  }

  // request by another actor
  def processTleRequest(req: TleRequest): Unit = {
    info(s"processing TLE request: $req")
    requestQueue = requestQueue :+ req

    if (authCookie.isDefined) {
      processNextRequest()
    } else {
      info(s"postponing $req until login is completed")
    }
  }

  protected def getLoginRequest: HttpRequest = {
    val uri = config.getStringOrElse("login-url", "https://www.space-track.org/ajaxauth/login")
    val headers = Seq.empty[HttpHeader]
    val entity = HttpEntity(`application/x-www-form-urlencoded`, ByteString( config.getVaultableString("auth")))

    HttpRequest(HttpMethods.POST,uri,headers,entity)
  }

  protected def getDataRequest (req: TleRequest): HttpRequest = {
    val uri = dataUrl.replace("$SAT_ID", req.satId.toString)
    HttpRequest( HttpMethods.GET, uri, authCookie.toList.map(_.cookieHdr))
  }

  protected def getHistoryDataRequest (req: TleRequest): HttpRequest = {
    val date = req.tleDate
    val d0 = date.toPreviousDay.format_yMd
    val d1 = date.format_yMd
    var uri = historyDataUrl.replace("$SAT_ID", req.satId.toString)
    uri = uri.replace("$BEGIN_DATE", d0)
    uri = uri.replace("$END_DATE", d1)

    HttpRequest( HttpMethods.GET, uri, authCookie.toList.map(_.cookieHdr))
  }

  protected def getLogoutRequest: HttpRequest = {
    val uri = config.getStringOrElse("logout-url", "https://www.space-track.org/auth/logout")
    HttpRequest( HttpMethods.GET, uri, authCookie.toList.map(_.cookieHdr))
  }

  def getSetCookie(resp: HttpResponse): Option[SetCookie] = {
    resp.headers.foreach {
      case `Set-Cookie`(cookie) =>
        val domain = cookie.domain.getOrElse(loginRequest.uri.authority.host.toString)
        val path = cookie.path.getOrElse("/")
        val setCookie = SetCookie(domain, path, cookie.name, cookie.value)
        cookie.maxAge match {
          case Some(n) => scheduleOnce( (n - 30).seconds){ startLogin() } // obtain new cookie before current one expires (2h)
          case None => // nothing to do
        }
        return Some(setCookie)

      case other =>
        debug(s"ignored http header: $other")
    }
    None
  }

  def saveTLEs(): Unit = {
    ifSome(dataDir) { dir=>
      val archive = new File(dir, "tle.txt")
      info(s"writing TLEs to $archive")

      val fos = new FileOutputStream(archive)
      val ps = new PrintStream(fos)
      tleEntries.foreach { e=>
        val tle = e._2
        ifSome(tle.name) { name=> ps.println(s"0 $name")}
        ps.println(tle.line1)
        ps.println(tle.line2)
      }
      ps.close()
    }
  }

  def readSavedTLEs(): Unit = {
    ifSome(dataDir) { dir =>
      val archive = new File(dir, "tle.txt")
      if (archive.isFile) {
        info(s"reading saved TLEs from $archive")

        try {
          val src = Source.fromFile(archive)
          val lineIterator = src.getLines()
          while (lineIterator.hasNext) {
            var nameLine: Option[String] = None
            val l = lineIterator.next()
            if (l.charAt(0) == '0') nameLine = Some(l)
            val l1 = if (nameLine.isDefined) lineIterator.next() else l
            val l2 = lineIterator.next()
            val satId = l2.substring(2, 7).toInt

            val tle = TLE( nameLine, l1, l2)
            tleEntries = tleEntries + (satId -> tle)
          }
          src.close()
        } catch {
          case x: Throwable => error(s"failed to read saved TLEs from $archive: $x")
        }
      }
    }
  }
}

/**
 * a simple request actor for test purposes.
 * this is here and not in race-space-test because it might be helpful for testing server access in production systems
 */
class TleTestRequester (val config: Config) extends PublishingRaceActor {
  val requests: Seq[(Time,TleRequest)] = config.getConfigSeq("requests").map( createRequest)
  val requestChannel = config.getString("request-from")

  def createRequest (cfg: Config): (Time,TleRequest) = {
    val satId = cfg.getInt("sat")
    val maxAge = cfg.getOptionalDurationTime("max-age")
    val date = cfg.getOptionalDateTime("date")
    val after = cfg.getOptionalDurationTime("after")
    (after -> TleRequest(self,satId,maxAge,date))
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    ifTrue (super.onStartRaceActor(originator)) {
      scheduleRequests()
    }
  }

  def scheduleRequests(): Unit = {
    requests.foreach { case (after, req) =>
      if (after.isDefined) {
        scheduleOnce(after.toFiniteDuration) { sendOn(requestChannel, req) }
      } else {
        sendOn(requestChannel, req)
      }
    }
  }

  override def handleMessage: Receive = {
    case tle: TLE => publish(tle)
  }
}