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

import java.lang.Thread.sleep

import akka.Done
import akka.actor.{ActorRef, Cancellable}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.model.{HttpHeader, StatusCodes}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.SubscribingRaceActor

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success}

/**
  * object that (optionally) translates incoming web socket Messages to BusEvent payloads
  * use this to de-serialize incoming messages
  */
trait WsMessageReader {
  def read (m: Message): Option[Any]
}

/**
  * default WsMessageReader that only processes strict message content, punblished either as String or Array[Byte]
  */
object DefaultWsMessageReader extends WsMessageReader {
  def read (m: Message): Option[Any] = m match {
    case tm: TextMessage.Strict => Some(tm.text)
    case bm: BinaryMessage.Strict => Some(bm.data.toArray[Byte])
    case _ => None  // ignore streams for now
  }
}

/**
  * writer that (optionally) translates BusEvent payloads to web socket Messages
  * use this to serialize outgoing messages
  */
trait WsMessageWriter {
  def write (o: Any): Option[Message]
}

/**
  * default WsMessageWriter that translates BusEvent payloads to strict TextMessages
  * TODO - we should support byte arrays as BinaryMessages here
  */
object DefaultWsMessageWriter extends WsMessageWriter {
  def write (o: Any): Option[Message] = {
    Some( TextMessage.Strict(o.toString))
  }
}



/**
  * actor that connects to a websocket server, sends messages received from the bus to it and publishes
  * websocket messages it gets from the server
  */
class WSAdapterActor (val config: Config) extends FilteringPublisher with SubscribingRaceActor
                                                                    with SourceQueueOwner with SSLContextUser {

  case object RetryConnect

  implicit val materializer: Materializer = Materializer.matFromSystem(context.system) // ?? do we want a shared materializer
  implicit val ec = scala.concurrent.ExecutionContext.global

  val http = Http(context.system)

  val wsUrl = config.getVaultableString("ws-url")
  val retryInterval = config.getFiniteDurationOrElse("retry-interval", 0.seconds) // 0 means we don't reconnect
  var retrySchedule: Option[Cancellable] = None

  val webSocketRequest = getWebSocketRequest
  val connectionContext = getConnectionContext

  var queue: Option[SourceQueueWithComplete[Message]] = None // set during RaceStart
  var isConnected: Boolean = false

  // watch out - these are NOT thread safe
  protected var reader: WsMessageReader = createReader
  protected var writer: WsMessageWriter = createWriter

  //--- end init


  // default behavior is to check for configured readers/writers or otherwise just pass data as strings

  protected def createReader = getConfigurableOrElse[WsMessageReader]("reader")(DefaultWsMessageReader)
  protected def createWriter = getConfigurableOrElse[WsMessageWriter]("writer")(DefaultWsMessageWriter)

  def setReader (newReader: WsMessageReader): Unit = reader = newReader
  def setWriter (newWriter: WsMessageWriter): Unit = writer = newWriter

  // override if we need to reset or drop connection if there is a send failure
  protected def handleSendFailure(msg: String): Unit = {
    warning(msg)
  }

  /**
    * override if messages should be filtered or translated
    */
  protected def processIncomingMessage (msg: Message): Unit = {
    info(s"received incoming message: $msg")
    reader.read(msg).foreach(publishFiltered)
  }

  protected def processOutgoingMessage (o: Any): Unit = {
    ifSome(queue) { q => // no need to translate anything if we are not connected
      writer.synchronized {
        writer.write(o) match {
          case Some(msg) =>
            q.offer(msg).onComplete { res =>
              res match {
                case Success(_) => // all good (TODO should we check for Enqueued here?)
                  info(s"message sent: $msg")

                case Failure(_) =>
                  handleSendFailure(s"message send failed: $msg")
              }
            }
          case None =>
            info(s"ignore outgoing $o")
        }
      }
    }
  }

  def getWebSocketRequest: WebSocketRequest = {
    var xhdrs = Seq.empty[HttpHeader]
    for (
      uid <- config.getOptionalVaultableString("uid");
      pw <- config.getOptionalVaultableString("pw")
    ) {
      xhdrs = Seq(Authorization(BasicHttpCredentials(uid,pw)))
    }

    WebSocketRequest(wsUrl,extraHeaders=xhdrs)
  }

  def getConnectionContext: ConnectionContext = {
    if (wsUrl.startsWith("wss://")) {

      getSSLContext("client-keystore") match {
        case Some(sslContext) =>
          info(s"using SSL config from client-keystore for $wsUrl")
          ConnectionContext.httpsClient(sslContext)
        case None => // nothing to set, we go with the default (or what is configured for Akka)
          info(s"using default connection context for $wsUrl")
          http.defaultClientHttpsContext
      }
    } else {
      http.defaultClientHttpsContext
    }
  }

  //--- connection/disconnection callbacks - override in subclasses
  def onConnect(): Unit = {}
  def onDisconnect(): Unit = {}

  def connect(): Future[Done.type] = {
    if (isConnected) return Future { Done } // we don't treat this an an error

    val inbound: Sink[Message,Future[Done]] = Sink.foreach( processIncomingMessage)
    val outbound: Source[Message, SourceQueueWithComplete[Message]] = createSourceQueue
    val flow: Flow[Message,Message,(Future[Done],SourceQueueWithComplete[Message])] = Flow.fromSinkAndSourceCoupledMat(inbound,outbound)(Keep.both)

    retrySchedule = None

    info(s"trying to connect to $wsUrl ...")
    val (upgradeResponse, (closed,qMat)) = http.singleWebSocketRequest(webSocketRequest,flow,connectionContext=connectionContext)
    queue = Some(qMat)

    closed.foreach { _ =>
      info(s"connection to $wsUrl closed by server")
      onDisconnect()
      isConnected = false
    }

    upgradeResponse.map { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        info(s"websocket connected: $wsUrl")
        onConnect()
        isConnected = true
        Done
      } else {
        //error(s"connection failed with ${upgrade.response.status}")
        // TODO we should also report the response entity here
        throw new RuntimeException(s"websocket connection to $wsUrl failed with: ${upgrade.response.status}")
      }
    }
  }

  def waitForConnection(): Boolean = {
    val connected = connect()

    try {
      Await.result(connected, 5.seconds)
      if (isConnected) {
       true
      } else {
        error(s"start failed - no connection to $wsUrl")
        false // should be already reported
      }
    } catch {
      case t: Throwable =>
        if (retryInterval.toMillis > 0) {
          queue = None
          warning(s"websocket connection to $wsUrl failed: ${t.getMessage}, retry in $retryInterval")
          retrySchedule = Some(scheduler.scheduleOnce(retryInterval, self, RetryConnect))
          true
        } else {
          error(s"websocket connection to $wsUrl failed: $t")
          false
        }
    }
  }

  def reconnect(): Unit = {
    if (isConnected) {
      info("trying to reconnect")
      ifSome(queue) { q =>
        q.complete()
        queue = None
      }
      onDisconnect()
      isConnected = false
      retrySchedule = Some(scheduler.scheduleOnce(retryInterval, self, RetryConnect))
    }
  }

  def waitForDisconnect (nRetries: Int): Unit = {
    val sleepMillis = 500
    var i=0
    while (i < nRetries) {
      if (isConnected) sleep(sleepMillis)
      i += 1
    }
    if (isConnected) {
      warning(s"no disconnect in ${i * sleepMillis} ms")
    } else {
      info("disconnected")
    }
  }

  def disconnect(): Unit = {
    retrySchedule.foreach( _.cancel())

    // TODO - is this the right way to explicitly close a web socket connection from an akka-http client?
    ifSome(queue){ _.complete() }
  }

  /**
    * override this if we don't want to automatically connect upon RaceStart
    */
  def isReadyToConnect: Boolean = true

  /**
    * note it does not mean we are already connected if this returns true
    * use onConnect() to trigger and post-connection processing
    */
  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)) {
      if (isReadyToConnect) {
        connect()
        true
      } else true
    } else false
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    disconnect()
    waitForDisconnect(5)

    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case BusEvent(sel,msg,sender) => processOutgoingMessage(msg)
    case RetryConnect => waitForConnection()
  }

}
