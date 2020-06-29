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

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import com.typesafe.config.Config
import gov.nasa.race.common.JsonPullParser.{ArrayStart, ObjectStart, UnQuotedValue}
import gov.nasa.race.common.{BufferedStringJsonPullParser, ConstAsciiSlice, JsonParseException, JsonWriter}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{DataConsumerRaceActor, ParentActor, PeriodicRaceActor, RaceDataConsumer}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer

/*
 * although this is a test for a complex static and bi-directional web-socket service it can also
 * be used as a building block for applications that need to update non-RACE clients with table data
 *
 * TODO - initialize fieldCatalog and provider data from configured json files
 */

object TabDataService {

  //--- internal data model
  case class FieldCatalog (fields: Seq[String])
  case class ProviderList (names: Seq[String])
  case class Provider (name: String, rev: Int, date: DateTime, fieldValues: immutable.Map[String,Int])

  //--- incoming message types
  sealed trait IncomingMessage
  case class RequestUserPermissions (uid: String, pw: Array[Byte]) extends IncomingMessage
  case class ProviderChange (uid: String, pw: Array[Byte], date: DateTime, provider: String, fieldValues: Seq[(String,Int)]) extends IncomingMessage

  // the (ordered) fields catalog
  val fieldCatalog = FieldCatalog(Seq("field_1", "field_2", "field_3", "field_4", "field_5", "field_6", "field_7", "field_8", "field_9"))

}





/**
  * the actor that collects/computes the model (data) and reports it to its data consumer RaceRouteInfo
  * this should not be concerned about any RaceRouteInfo specifics (clients, data formats etc.)
  */
class TabDataServiceActor (_dc: RaceDataConsumer, _conf: Config) extends DataConsumerRaceActor(_dc,_conf) {
  import TabDataService._
  var nextProviderIdx = 0

  val providerList = ProviderList(Seq("provider_1", "provider_2", "provider_3","provider_4","provider_5","provider_6","provider_7","provider_8","provider_9"))

  val data = mutable.Map[String,Provider](
    "provider_1" -> Provider("provider_1", 1, DateTime.now, Map(("field_1",1100), ("field_3",1300), ("field_8",1800))),
    "provider_2" -> Provider("provider_2", 1, DateTime.now, Map(("field_3",2300), ("field_4",2400), ("field_6",2600))),
    "provider_3" -> Provider("provider_3", 1, DateTime.now, Map(("field_1",3100), ("field_2",3200), ("field_5",3500), ("field_8",3800))),
    "provider_4" -> Provider("provider_4", 1, DateTime.now, Map(("field_1",4100), ("field_3",4300), ("field_4",4400), ("field_6",4600), ("field_7",4700))),
    "provider_5" -> Provider("provider_5", 1, DateTime.now, Map(("field_2",5200), ("field_4",5400), ("field_7",5700), ("field_8",5800))),
    "provider_6" -> Provider("provider_6", 1, DateTime.now, Map(("field_5",6500), ("field_6",6600), ("field_7",6700), ("field_9",6900))),
    "provider_7" -> Provider("provider_7", 1, DateTime.now, Map(("field_2",7200), ("field_4",7400), ("field_7",7700))),
    "provider_8" -> Provider("provider_8", 1, DateTime.now, Map(("field_3",8300), ("field_4",8400), ("field_9",8900))),
    "provider_9" -> Provider("provider_9", 1, DateTime.now, Map(("field_2",9200), ("field_3",9300), ("field_4",9400), ("field_5",9500), ("field_6",9600), ("field_8",9800)))
  )

  override def onStartRaceActor(originator: ActorRef): Boolean = {

    // set the initial data
    setData(fieldCatalog)
    setData(providerList)
    data.foreach(e=> setData(e._2))

    super.onStartRaceActor(originator)
  }

  override def handleMessage: PartialFunction[Any, Unit] = {
    case change:ProviderChange => // we get this directly from the routeinfo
      updateProvider(change)

    case BusEvent(_, data: Any, _) => // nothing yet
      //setData(data)
  }

  def updateProvider (change: ProviderChange): Unit = {
    data.get(change.provider) match {
      case Some(p) => // is it a provider we know
        var nChanged = 0
        var fv = p.fieldValues

        for (e <- change.fieldValues) {
          val fieldName = e._1
          if (fieldCatalog.fields.contains(fieldName)) { // is it a field we know
            fv = fv + (fieldName -> e._2)
            nChanged += 1
          }
        }
        if (nChanged > 0){
          val pʹ = p.copy(rev = p.rev+1,date=change.date,fieldValues=fv)
          data.update(pʹ.name, pʹ)
          setData(pʹ)
        }

      case None => // unknown provider
        warning(s"attempt to update unknown provider: ${change.provider}")
    }
  }
}


/**
  * the RaceRouteInfo that serves the content to display and update Provider data
  */
class TabDataService (parent: ParentActor, config: Config) extends SiteRoute(parent,config)
                                                                  with PushWSRaceRoute with RaceDataConsumer {
  import TabDataService._

  object IncomingMessageParser {
    //--- lexical constants
    val _msgType_ = ConstAsciiSlice("msgType")
    val _uid_ = ConstAsciiSlice("uid")
    val _pw_ = ConstAsciiSlice("pw")
    val _date_ = ConstAsciiSlice("date")
    val _requestUserPermissions_ = ConstAsciiSlice("requestUserPermissions")
    val _providerChange_ = ConstAsciiSlice("providerChange")
  }

  class IncomingMessageParser extends BufferedStringJsonPullParser {
    import IncomingMessageParser._

    def parse (msg: String): Option[IncomingMessage] = {
      initialize(msg)

      try {
        matchObjectStart
        if (parseNextValue == ObjectStart) {
          member match {
            case `_requestUserPermissions_` => parseRequestUserPermissions(msg)
            case `_providerChange_` => parseProviderChange(msg)
            case m =>
              warning(s"ignoring unknown message type '$m''")
              None
          }

        } else {
          warning(s"ignoring unknown message: $msg")
          None
        }

      } catch {
        case x: JsonParseException =>
          x.printStackTrace
          warning(s"ignoring malformed incoming message '$msg'")
          None
      }
    }

    def parseRequestUserPermissions (msg: String): Option[RequestUserPermissions] = {
      if (parseNextValue == ArrayStart) {
        val uid = member.toString
        val pw = new ArrayBuffer[Byte]
        parseArrayElements { res=>
          if (res == UnQuotedValue) pw.addOne(value.toByte)
        }
        Some(RequestUserPermissions(uid,pw.toArray))
      } else {
        warning(s"malformed requestUserPermission: $msg")
        None
      }
    }

    def parseProviderChange (msg: String): Option[ProviderChange] = {
      if (parseNextValue == ArrayStart) {
        val uid = member.toString
        val pw = new ArrayBuffer[Byte]
        parseArrayElements { res=>
          if (res == UnQuotedValue) pw.addOne(value.toByte)
        }

        val date = DateTime.ofEpochMillis(readUnQuotedMember(_date_).toLong)
        if (parseNextValue == ObjectStart) {
          val providerName = member.toString
          var fieldValues: Seq[(String,Int)] = Seq.empty
          parseObjectMembers { res=>
            if (res == UnQuotedValue) fieldValues = fieldValues :+ (member.toString,value.toInt)
            else warning(s"ignoring malformed ProviderChange field value: $member: $value")
          }
          Some(ProviderChange(uid,pw.toArray,date,providerName,fieldValues))
        } else {
          warning(s"ignoring ProviderChange message with malformed or missing <provider>: $msg")
          None
        }
      } else {
        warning(s"ignoring ProviderChange message with malformed or missing <uid>: $msg")
        None
      }
    }
  }

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  val incomingParser = new IncomingMessageParser
  val writer = new JsonWriter

  // we store the data in a format that is ready to send
  var fieldCatalog: Option[TextMessage] = None
  var providerList: Option[TextMessage] = None
  val providerData = mutable.Map.empty[String,TextMessage]
  val userPermissions: Map[String,String] = readUserPermissions

  def readUserPermissions: Map[String,String] = {
    Map(("gonzo", """{ "provider_2": ".*" }"""))
  }

  def serializeProviderList (providerList: ProviderList): String = {
    writer.clear
      .beginObject
        .writeMemberName("providerCatalog")
        .writeStringValues(providerList.names)
      .endObject
      .toJson
  }

  def serializeFieldCatalog (fieldCatalog: FieldCatalog): String = {
    writer.clear
      .beginObject
        .writeMemberName("fieldCatalog")
        .writeStringValues(fieldCatalog.fields)
      .endObject
      .toJson
  }

  def serializeProvider (provider: Provider): String = {
    writer.clear
      .beginObject
        .writeMemberName("providerData")
          .beginObject
          .writeStringMember("name", provider.name)
          .writeIntMember("rev", provider.rev)
          .writeLongMember("date", provider.date.toEpochMillis)
          .writeMemberName("fieldValues")
          .writeIntMembers(provider.fieldValues)
          .endObject
        .endObject
      .toJson
  }

  override protected def instantiateActor: DataConsumerRaceActor = new TabDataServiceActor(this,config)

  override def setData (data:Any): Unit = {
    data match {
      case p: Provider =>
        val msg = TextMessage(serializeProvider(p))
        providerData.put(p.name,msg)
        push(msg)

      case p: ProviderList =>
        val msg = TextMessage(serializeProviderList(p))
        providerList = Some(msg)
        push(msg)

      case c: FieldCatalog =>
        val msg = TextMessage(serializeFieldCatalog(c))
        fieldCatalog = Some(msg)
        push(msg)
    }
  }

  override protected def handleIncoming (m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        incomingParser.parse(tm.text) match {
          case Some(RequestUserPermissions(uid,pw)) =>
            // TODO - pw ignored for now
            userPermissions.get(uid) match {
              case Some(permissions) =>
                TextMessage(s"""{"userPermissions":{"uid":"$uid","permissions":$permissions}}""") :: Nil
              case None => // unknown user
                TextMessage(s"""{"userPermissions":{"uid":"$uid","permissions":null}}""") :: Nil
            }
          case Some(pc@ProviderChange(uid,pw,date,provider,fieldValues)) =>
            actorRef ! pc // we send the ProviderChange to our actor, which manages the data model and reports chages back for publishing
            Nil

          case _ => Nil
        }

      case _ =>
        super.handleIncoming(m)
    }
  }

  override def route: Route = {
    get {
      path(wsPathMatcher) {
        promoteToWebSocket
      }
    } ~ siteRoute
  }

  override protected def initializeConnection (conn: WebSocketPushConnection): Unit = {
    fieldCatalog.foreach( pushTo(conn,_))
    providerList.foreach( pushTo(conn,_))
    providerData.foreach( e=> pushTo(conn,e._2))
  }

}
