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
import gov.nasa.race.common.{BufferedStringJsonPullParser, CharSeqByteSlice, JsonParseException, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{DataConsumerRaceActor, ParentActor, RaceDataConsumer, RaceException}
import gov.nasa.race.http.TabDataService._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import scala.collection.immutable.{Iterable, ListMap}
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.duration.Duration

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

  sealed trait FieldValue {
    def toLong: Long
    def toDouble: Double
    def toJson: String
  }
  case class LongValue(value: Long) extends FieldValue {
    def toLong: Long = value
    def toDouble: Double = value.toDouble
    def toJson = value.toString
  }
  case class DoubleValue(value: Double) extends FieldValue {
    def toLong: Long = Math.round(value)
    def toDouble: Double = value
    def toJson = value.toString
  }

  sealed trait Field {
    val id: String
    val info: String
    val header: Option[String]

    def valueFrom(bs: CharSeqByteSlice): FieldValue
    def typeName: String

    def serializeTo (w:JsonWriter): Unit = {
      w.writeObject { w=>
        w.writeStringMember("id", id)
        w.writeStringMember("info", info)
        w.writeStringMember("type", typeName)
        header.foreach( w.writeStringMember("header", _))
      }
    }
  }
  sealed trait FieldType {
    def instantiate (id: String, info: String, header: Option[String]): Field
    def typeName: String
  }

  object LongField extends FieldType {
    def instantiate (id: String, info: String, header: Option[String]): Field = LongField(id,info,header)
    def typeName: String = "integer"
  }
  case class LongField (id: String, info: String, header: Option[String]) extends Field {
    def valueFrom (bs: CharSeqByteSlice) = LongValue(bs.toLong)
    def typeName = LongField.typeName
  }

  object DoubleField extends FieldType {
    def instantiate (id: String, info: String, header: Option[String]): Field = DoubleField(id,info,header)
    def typeName: String = "rational"
  }
  case class DoubleField (id: String, info: String, header: Option[String]) extends Field {
    def valueFrom (bs: CharSeqByteSlice) = DoubleValue(bs.toDouble)
    def typeName = DoubleField.typeName
  }

  case class Provider (id: String, info: String, update: Duration) {
    def serializeTo (w: JsonWriter): Unit = {
      w.writeObject { w=>
        w.writeStringMember("id", id)
        w.writeStringMember("info", info)
        w.writeLongMember("update", update.toMillis)
      }
    }
  }

  case class FieldCatalog (title: String, rev: Int, fields: ListMap[String,Field]) {
    def serializeTo (w: JsonWriter): Unit = {
      w.clear.writeObject( _
        .writeMemberObject("fieldCatalog") { _
          .writeStringMember("title", title)
          .writeIntMember("rev", rev)
          .writeArrayMember("fields"){ w=>
            for (field <- fields.valuesIterator){
              field.serializeTo(w)
            }
          }
        }
      )
    }
  }

  case class ProviderCatalog (title: String, rev: Int, providers: ListMap[String,Provider]) {
    def serializeTo (w: JsonWriter): Unit = {
      w.clear.writeObject(
        _.writeMemberObject("providerCatalog") {
          _.writeStringMember("title", title)
            .writeIntMember("rev", rev)
            .writeArrayMember("providers") { w =>
              for (provider <- providers.valuesIterator) {
                provider.serializeTo(w)
              }
            }
        }
      )
    }
  }

  case class ProviderData(id: String, rev: Int, date: DateTime, fieldValues: immutable.Map[String,FieldValue])

  type PermSpec = (String,String)  // regular expressons for provider- and related field- patterns
  type Perms = Seq[PermSpec]
  case class UserPermissions (rev: Int, users: immutable.Map[String,Perms])

  //--- incoming message types
  sealed trait IncomingMessage
  case class RequestUserPermissions (uid: String, pw: Array[Byte]) extends IncomingMessage
  case class ProviderChange (uid: String, pw: Array[Byte], date: DateTime, provider: String, fieldValues: Seq[(String,FieldValue)]) extends IncomingMessage
}

object FieldCatalogParser {
  //--- lexical constants
  private val _title_     = asc("title")
  private val _rev_       = asc("rev")
  private val _fields_    = asc("fields")
  private val _id_        = asc("id")
  private val _type_      = asc("type")
  private val _integer_   = asc(LongField.typeName)
  private val _rational_  = asc(DoubleField.typeName)
  private val _info_      = asc("info")
  private val _header_    = asc("header")
}

class FieldCatalogParser extends UTF8JsonPullParser {
  import FieldCatalogParser._

  private def readFieldType (): FieldType = {
    val v = readQuotedMember(_type_)
    if (v == _integer_) LongField
    else if (v == _rational_) DoubleField
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
        val title = readQuotedMember(_title_).toString
        val rev = readUnQuotedMember(_rev_).toInt

        var fields = ListMap.empty[String,Field]
        foreachInNextArrayMember(_fields_) {
          val field = readNextObject {
            val id = readQuotedMember(_id_).toString
            val fieldType = readFieldType()
            val info = readQuotedMember(_info_).toString
            val header = readOptionalHeader()
            fieldType.instantiate(id,info,header)
          }
          fields = fields + (field.id -> field)
        }
        Some(FieldCatalog(title,rev,fields))
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed fieldCatalog: ${x.getMessage}")
        None
    }
  }
}

object ProviderCatalogParser {
  //--- lexical constants
  private val _title_     = asc("title")
  private val _rev_ = asc("rev")
  private val _providers_ = asc("providers")
  private val _id_ = asc("id")
  private val _info_ = asc("info")
  private val _update_ = asc("update")
}

class ProviderCatalogParser extends UTF8JsonPullParser {
  import ProviderCatalogParser._

  def parse (buf: Array[Byte]): Option[ProviderCatalog] = {
    initialize(buf)
    try {
      readNextObject {
        val title = readQuotedMember(_title_).toString
        val rev = readUnQuotedMember(_rev_).toInt

        var providers = ListMap.empty[String,Provider]
        foreachInNextArrayMember(_providers_) {
          readNextObject {
            val id = readQuotedMember(_id_).toString
            val info = readQuotedMember(_info_).toString
            val update = readQuotedMember(_update_)
            val provider = Provider(id, info, update.toDuration)
            providers = providers + (id -> provider)
          }
        }
        Some(ProviderCatalog(title,rev,providers))
      }

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerCatalog: ${x.getMessage}")
        None
    }
  }
}

class ProviderDataParser (fieldCatalog: FieldCatalog) extends UTF8JsonPullParser {
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
      val fieldValues = readSomeNextObjectMemberInto[FieldValue,mutable.Map[String,FieldValue]](_fieldValues_,mutable.Map.empty){
        val v = readUnQuotedValue()
        val fieldId = member.toString
        fieldCatalog.fields.get(fieldId) match {
          case Some(field) => Some((fieldId,field.valueFrom(v)))
          case None => warning(s"skipping unknown field $fieldId"); None
        }
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
class IncomingMessageParser (fieldCatalog: FieldCatalog, providerCatalog: ProviderCatalog) extends BufferedStringJsonPullParser {
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

  // {"providerChange":{  "<uid>":[byte..], "date":<long>, "<provider>":{ "<field>": <value>, .. }}
  def parseProviderChange (msg: String): Option[ProviderChange] = {
    var fieldValues = Seq.empty[(String,FieldValue)]

    val uid = readArrayMemberName().toString
    val pw = readCurrentByteArrayInto(new ArrayBuffer[Byte]).toArray
    val date = DateTime.ofEpochMillis(readUnQuotedMember(_date_).toLong)
    val providerName = readObjectMemberName().toString
    // TODO - we could check for valid providers here
    foreachInCurrentObject {
      val v = readUnQuotedValue()
      val fieldId = member.toString
      fieldCatalog.fields.get(fieldId) match {
        case Some(field) => fieldValues = fieldValues :+ (fieldId -> field.valueFrom(v))
        case None => warning(s"skipping unknown field $fieldId")
      }
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
  val providerData = readProviderData(fieldCatalog,providerCatalog)

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

  def readProviderData (fCatalog: FieldCatalog, pCatalog: ProviderCatalog): mutable.Map[String,ProviderData] = {
    val map = mutable.Map.empty[String,ProviderData]

    val dataDir = config.getExistingDir("provider-data")
    for (p <- pCatalog.providers) {
      val id = p._1
      val f = new File(dataDir, s"$id.json")
      if (f.isFile) {
        val parser = new ProviderDataParser(fCatalog)
        parser.setLogging(info,warning,error)
        parser.parse(FileUtils.fileContentsAsBytes(f).get) match {
          case Some(providerData) =>
            map += id -> providerData
          case None =>
            throw new RaceException(f"error parsing provider-data in $f")
        }
      } else {
        warning(s"no provider data for '$id'")
        map += id -> ProviderData(id,0,DateTime.now,Map.empty[String,FieldValue])
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
          val fieldId = e._1
          fieldCatalog.fields.get(fieldId) match {
            case Some(field) =>
              fv = fv + (fieldId -> e._2)
              nChanged += 1
            case None => warning(s"ignoring unknown field change $fieldId")
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

  var parser: Option[IncomingMessageParser] = None // we can't parse until we have catalogs
  val writer = new JsonWriter

  var providerCatalog: Option[ProviderCatalog] = None
  var fieldCatalog: Option[FieldCatalog] = None

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
    fieldCatalog.serializeTo(writer)
    writer.toJson
  }

  def serializeProviderCatalog (providerCatalog: ProviderCatalog): String = {
    providerCatalog.serializeTo(writer)
    writer.toJson
  }

  def serializeProviderData (providerData: ProviderData): String = {
    writer.clear.writeObject( _
      .writeMemberObject("providerData") { _
        .writeStringMember("id", providerData.id)
        .writeIntMember("rev", providerData.rev)
        .writeLongMember("date", providerData.date.toEpochMillis)
        .writeMemberObject("fieldValues") { w =>
          providerData.fieldValues.foreach { fv =>
            w.writeMemberName(fv._1)
            w.writeUnQuotedValue(fv._2.toJson)
          }
        }
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

  protected def createIncomingMessageParser: Option[IncomingMessageParser] = {
    for ( fc <- fieldCatalog; pc <- providerCatalog) yield {
      val p = new IncomingMessageParser(fc,pc)
      p.setLogging(info,warning,error)
      p
    }
  }

  /**
    * this is called from our actor
    * NOTE this is executed async - avoid data races
    */
  override def setData (data:Any): Unit = {
    data match {
      case p: ProviderData =>
        val msg = TextMessage(serializeProviderData(p))
        providerDataMessage.put(p.id,msg)
        push(msg)

      case c: ProviderCatalog =>
        val msg = TextMessage(serializeProviderCatalog(c))
        providerCatalog = Some(c)
        providerCatalogMessage = Some(msg)
        parser = createIncomingMessageParser
        push(msg)

      case c: FieldCatalog =>
        val msg = TextMessage(serializeFieldCatalog(c))
        fieldCatalog = Some(c)
        fieldCatalogMessage = Some(msg)
        parser = createIncomingMessageParser
        push(msg)
    }
  }

  override protected def handleIncoming (m: Message): Iterable[Message] = {
    m match {
      case tm: TextMessage.Strict =>
        parser match {
          case Some(p) =>
            p.parse(tm.text) match {
              case Some(RequestUserPermissions(uid,pw)) =>
                // TODO - pw ignored for now
                val perms = userPermissions.users.getOrElse(uid,Seq.empty[PermSpec])
                TextMessage(serializeUserPermissions(uid,perms)) :: Nil

              case Some(pc@ProviderChange(uid,pw,date,provider,fieldValues)) =>
                // TODO - check user credentials and permissions
                actorRef ! pc // we send the ProviderChange to our actor, which manages the data model and reports changes back for publishing
                Nil

              case _ =>
                warning(s"ignoring unknown incoming message ${tm.text}")
                Nil
            }
          case None =>
            warning("ignoring incoming message - no catalogs yet")
            Nil
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
