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

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import com.typesafe.config.Config
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonParseException, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{DataConsumerRaceActor, ParentActor, RaceDataConsumer, RaceException}
import gov.nasa.race.http.TabDataService._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import scala.collection.immutable.Iterable
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}

/*
 * a generic web application service that pushes provider data to connected clients through a web socket
 * and allows clients / authorized users to update this data through JSON messages. The data model consists of
 * three elements:
 *
 *   - field catalog: defines the ordered set of fields that can be recorded for each provider
 *   - provider catalog: defines the ordered set of providers for which data can be recorded
 *   - {provider-data}: maps from field ids to Int values for each provider
 */

object TabDataService {

  //--- internal data model
  sealed trait FieldType {
    def typeName: String
  }
  case object IntegerFieldType extends FieldType {
    def typeName = "int"
  }
  case object RationalFieldType extends FieldType {
    def typeName = "rat"
  }

  sealed trait FieldValue {
    def toLong: Long
    def toDouble: Double
  }
  case class IntegerField (value: Long) extends FieldValue {
    def toLong: Long = value
    def toDouble: Double = value.toDouble
  }
  case class RationalField (value: Double) extends FieldValue {
    def toLong: Long = Math.round(value)
    def toDouble: Double = value
  }

  case class Field (id: String, fieldType: FieldType, info: String, header: Option[String])
  case class Provider (id: String, info: String)

  case class FieldCatalog (rev: Int, fields: Seq[Field])
  case class ProviderCatalog (rev: Int, providers: Seq[Provider])
  case class ProviderData(id: String, rev: Int, date: DateTime, fieldValues: immutable.Map[String,Int])

  type PermSpec = (String,String)  // regular expressons for provider- and related field- patterns
  type Perms = Seq[PermSpec]
  case class UserPermissions (rev: Int, users: immutable.Map[String,Perms])

  //--- incoming message types
  sealed trait IncomingMessage
  case class RequestUserPermissions (uid: String, pw: Array[Byte]) extends IncomingMessage
  case class ProviderChange (uid: String, pw: Array[Byte], date: DateTime, provider: String, fieldValues: Seq[(String,Int)]) extends IncomingMessage
}

class FieldCatalogParser extends UTF8JsonPullParser {
  //--- lexical constants
  private val _rev_       = asc("rev")
  private val _fields_    = asc("fields")
  private val _id_        = asc("id")
  private val _type_      = asc("type")
  private val _integer_   = asc(IntegerFieldType.typeName)
  private val _rational_  = asc(RationalFieldType.typeName)
  private val _info_      = asc("info")
  private val _header_    = asc("header")

  private def readFieldType (): FieldType = {
    val v = readQuotedMember(_type_)
    if (v == _integer_) IntegerFieldType
    else if (v == _rational_) RationalFieldType
    else throw exception(s"unknown field type: $v")
  }

  private def readOptionalHeader (): Option[String] = {
    if (!isLevelEnd && isNextScalarMember(_header_)) {
      if (!isQuotedValue) throw exception("illegal header value")
      // TODO - should we check the value here?
      Some(value.toString)
    } else {
      None
    }
  }

  def parse (buf: Array[Byte]): Option[FieldCatalog] = {
    initialize(buf)
    try {
      readNextObject {
        val rev = readUnQuotedMember(_rev_).toInt
        val fields = readNextArrayMemberInto(_fields_,new ArrayBuffer[Field]){
          readNextObject {
            val id = readQuotedMember(_id_).toString
            val fieldType = readFieldType()
            val info = readQuotedMember(_info_).toString
            val header = readOptionalHeader()
            Field(id,fieldType,info,header)
          }
        }.toSeq
        Some(FieldCatalog(rev,fields))
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed fieldCatalog: ${x.getMessage}")
        None
    }
  }
}

class ProviderCatalogParser extends UTF8JsonPullParser {
  //--- lexical constants
  private val _rev_ = asc("rev")
  private val _providers_ = asc("providers")
  private val _id_ = asc("id")
  private val _info_ = asc("info")

  def parse (buf: Array[Byte]): Option[ProviderCatalog] = {
    initialize(buf)
    try {
      readNextObject {
        val rev = readUnQuotedMember(_rev_).toInt
        val providers = readNextArrayMemberInto(_providers_,new ArrayBuffer[Provider]){
          readNextObject {
            val id = readQuotedMember(_id_).toString
            val info = readQuotedMember(_info_).toString
            Provider(id, info)
          }
        }.toSeq
        Some(ProviderCatalog(rev,providers))
      }

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerCatalog: ${x.getMessage}")
        None
    }
  }
}

class ProviderDataParser extends UTF8JsonPullParser {
  private val _id_ = asc("id")
  private val _rev_ = asc("rev")
  private val _date_ = asc("date")
  private val _fieldValues_ = asc("fieldValues")

  def parse (buf: Array[Byte]): Option[ProviderData] = {
    initialize(buf)

    try {
      ensureNextIsObjectStart()
      val id = readQuotedMember(_id_).toString
      val rev = readUnQuotedMember(_rev_).toInt
      val date = DateTime.parseYMDT(readQuotedMember(_date_))
      val fieldValues = readNextObjectMemberInto[Int,mutable.Map[String,Int]](_fieldValues_,mutable.Map.empty){
        val value = readUnQuotedValue().toInt
        (member.toString,value)
      }.toMap

      Some(ProviderData(id,rev,date,fieldValues))

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerData: ${x.getMessage}")
        None
    }
  }
}

class UserPermissionsParser extends UTF8JsonPullParser {
  private val _rev_ = asc("rev")
  private val _users_ = asc("users")
  private val _providerPattern_ = asc("providerPattern")
  private val _fieldPattern_ = asc("fieldPattern")

  def parse (buf: Array[Byte]): Option[UserPermissions] = {
    initialize(buf)

    try {
      ensureNextIsObjectStart()
      val rev = readUnQuotedMember(_rev_).toInt
      val users = readNextObjectMemberInto[Perms,mutable.Map[String,Perms]](_users_,mutable.Map.empty){
        val user = readArrayMemberName().toString
        val perms = readCurrentArrayInto(ArrayBuffer.empty[PermSpec]) {
          readNextObject {
            val providerPattern = readQuotedMember(_providerPattern_).toString
            val fieldPattern = readQuotedMember(_fieldPattern_).toString
            (providerPattern, fieldPattern)
          }
        }.toSeq
        (user,perms)
      }.toMap
      Some(UserPermissions(rev,users))

    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed userPermissions: ${x.getMessage}, idx=$idx")
        None
    }
  }
}

/**
  * parser for messages received from websocket clients
  */
class IncomingMessageParser extends BufferedStringJsonPullParser {
  //--- lexical constants
  private val _date_ = asc("date")
  private val _requestUserPermissions_ = asc("requestUserPermissions")
  private val _providerChange_ = asc("providerChange")

  def parse (msg: String): Option[IncomingMessage] = {
    initialize(msg)

    try {
      ensureNextIsObjectStart() // all messages are objects
      readObjectMemberName() match {  // with a single payload object member
        case `_requestUserPermissions_` => parseRequestUserPermissions(msg)
        case `_providerChange_` => parseProviderChange(msg)
        case m =>
          warning(s"ignoring unknown message '$m''")
          None
      }
    } catch {
      case x: JsonParseException =>
        //x.printStackTrace
        warning(s"ignoring malformed incoming message '$msg'")
        None
    }
  }

  // {"requestUserPermissions":{ "<uid>":[byte..]}
  def parseRequestUserPermissions (msg: String): Option[RequestUserPermissions] = {
    val uid = readArrayMemberName().toString
    val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray

    Some(RequestUserPermissions(uid,pw))
  }

  // {"providerChange":{  "<uid>":[byte..], "date":<long>, "<provider>":{ "<field>": <int>, .. }}
  def parseProviderChange (msg: String): Option[ProviderChange] = {
    var fieldValues: Seq[(String,Int)] = Seq.empty

    val uid = readArrayMemberName().toString
    val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray
    val date = DateTime.ofEpochMillis(readUnQuotedMember(_date_).toLong)
    val providerName = readObjectMemberName().toString
    foreachInCurrentObject {
      val n = readUnQuotedValue().toInt
      fieldValues = fieldValues :+ (member.toString,n)
    }

    Some(ProviderChange(uid,pw,date,providerName,fieldValues))
  }
}

/**
  * the actor that collects/computes the model (data) and reports it to its data consumer RaceRouteInfo
  * this should not be concerned about any RaceRouteInfo specifics (clients, data formats etc.)
  */
class TabDataServiceActor (_dc: RaceDataConsumer, _conf: Config) extends DataConsumerRaceActor(_dc,_conf) {
  import TabDataService._

  //--- the data

  var fieldCatalog: FieldCatalog = readFieldCatalog
  var providerCatalog: ProviderCatalog = readProviderCatalog
  val providerData = readProviderData

  //--- actor interface

  override def onStartRaceActor(originator: ActorRef): Boolean = {

    // set the initial data
    setData(fieldCatalog)
    setData(providerCatalog)
    providerData.foreach(e=> setData(e._2))

    super.onStartRaceActor(originator)
  }

  override def handleMessage: PartialFunction[Any, Unit] = {
    case change:ProviderChange => // we get this directly from the routeinfo(s)
      updateProvider(change)

    case BusEvent(_, data: Any, _) => // nothing yet
      //setData(data)
  }

  //--- data model init and update

  def readFieldCatalog: FieldCatalog = {
    val parser = new FieldCatalogParser
    parser.setLogging(info,warning,error)
    config.translateFile("field-catalog")(parser.parse) match {
      case Some(fieldCatalog) => fieldCatalog
      case None => throw new RaceException("error parsing field-catalog")
    }
  }

  def readProviderCatalog: ProviderCatalog = {
    val parser = new ProviderCatalogParser
    parser.setLogging(info,warning,error)
    config.translateFile("provider-catalog")(parser.parse) match {
      case Some(providerCatalog) => providerCatalog
      case None => throw new RaceException("error parsing provider-catalog")
    }
  }

  def readProviderData: mutable.Map[String,ProviderData] = {
    val map = mutable.Map.empty[String,ProviderData]

    val dataDir = config.getExistingDir("provider-data")
    for (p <- providerCatalog.providers) {
      val id = p.id
      val f = new File(dataDir, s"$id.json")
      if (f.isFile) {
        val parser = new ProviderDataParser
        parser.setLogging(info,warning,error)
        parser.parse(FileUtils.fileContentsAsBytes(f).get) match {
          case Some(providerData) =>
            map += id -> providerData
          case None =>
            throw new RaceException(f"error parsing provider-data in $f")
        }
      } else {
        warning(s"no provider data for '$id'")
        map += id -> ProviderData(id,0,DateTime.now,Map.empty[String,Int])
      }
    }

    map
  }

  def updateProvider (change: ProviderChange): Unit = {
    providerData.get(change.provider) match {
      case Some(p) => // is it a provider we know
        var nChanged = 0
        var fv = p.fieldValues

        for (e <- change.fieldValues) {
          val fieldName = e._1
          if (fieldCatalog.fields.find( _.id == fieldName).isDefined) { // is it a field we know
            fv = fv + (fieldName -> e._2)
            nChanged += 1
          }
        }
        if (nChanged > 0){
          val pʹ = p.copy(rev = p.rev+1,date=change.date,fieldValues=fv)
          providerData.update(pʹ.id, pʹ)
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

  val wsPath = s"$requestPrefix/ws"
  val wsPathMatcher = PathMatchers.separateOnSlashes(wsPath)

  val parser = new IncomingMessageParser
  parser.setLogging(info,warning,error)
  val writer = new JsonWriter

  // we store the data in a format that is ready to send
  var fieldCatalogMessage: Option[TextMessage] = None
  var providerCatalogMessage: Option[TextMessage] = None
  val providerDataMessage = mutable.Map.empty[String,TextMessage]

  val userPermissions: UserPermissions = readUserPermissions

  def readUserPermissions: UserPermissions = {
    val parser = new UserPermissionsParser
    parser.setLogging(info,warning,error)
    config.translateFile("user-permissions")(parser.parse) match {
      case Some(userPermissions) => userPermissions
      case None => throw new RaceException("error parsing user-permissions")
    }
  }

  def serializeFieldCatalog (fieldCatalog: FieldCatalog): String = {
    writer.clear.writeObject( _
      .writeMemberObject("fieldCatalog") { _
        .writeIntMember("rev", fieldCatalog.rev)
        .writeArrayMember("fields"){ w=>
          for (field <- fieldCatalog.fields){
            w.beginObject
            w.writeStringMember("id",field.id)
            w.writeStringMember("info", field.info)
            w.writeStringMember("type", field.fieldType.typeName)
            field.header.foreach( w.writeStringMember("header",_))
            w.endObject
          }
        }
      }
    ).toJson
  }

  def serializeProviderCatalog (providerCatalog: ProviderCatalog): String = {
    writer.clear.writeObject( _
      .writeMemberObject("providerCatalog") { _
        .writeIntMember("rev", providerCatalog.rev)
        .writeArrayMember("providers"){ w=>
          for (provider <- providerCatalog.providers){
            w.beginObject
            w.writeStringMember("id", provider.id)
            w.writeStringMember("info", provider.info)
            w.endObject
          }
        }
      }
    ).toJson
  }

  def serializeProviderData (providerData: ProviderData): String = {
    writer.clear.writeObject( _
      .writeMemberObject("providerData") { _
        .writeStringMember("id", providerData.id)
        .writeIntMember("rev", providerData.rev)
        .writeLongMember("date", providerData.date.toEpochMillis)
        .writeIntMembersMember("fieldValues", providerData.fieldValues)
      }
    ).toJson
  }

  def serializeUserPermissions (user: String, perms: Perms): String = {
    writer.clear.writeObject( _
      .writeMemberObject( "userPermissions") { _
        .writeStringMember("uid", user)
        .writeStringMembersMember("permissions", perms)
      }
    ).toJson
  }

  override protected def instantiateActor: DataConsumerRaceActor = new TabDataServiceActor(this,config)

  override def setData (data:Any): Unit = {
    data match {
      case p: ProviderData =>
        val msg = TextMessage(serializeProviderData(p))
        providerDataMessage.put(p.id,msg)
        push(msg)

      case p: ProviderCatalog =>
        val msg = TextMessage(serializeProviderCatalog(p))
        providerCatalogMessage = Some(msg)
        push(msg)

      case c: FieldCatalog =>
        val msg = TextMessage(serializeFieldCatalog(c))
        fieldCatalogMessage = Some(msg)
        push(msg)
    }
  }

  override protected def handleIncoming (m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parser.parse(tm.text) match {
          case Some(RequestUserPermissions(uid,pw)) =>
            // TODO - pw ignored for now
            val perms = userPermissions.users.getOrElse(uid,Seq.empty[PermSpec])
            TextMessage(serializeUserPermissions(uid,perms)) :: Nil

          case Some(pc@ProviderChange(uid,pw,date,provider,fieldValues)) =>
            // TODO - check user credentials and permissions
            actorRef ! pc // we send the ProviderChange to our actor, which manages the data model and reports changes back for publishing
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
    fieldCatalogMessage.foreach( pushTo(conn,_))
    providerCatalogMessage.foreach( pushTo(conn,_))
    providerDataMessage.foreach(e=> pushTo(conn,e._2))
  }

}
