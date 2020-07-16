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
package gov.nasa.race.http.tabdata

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonParseException}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * root type for client messages to the TabDataService
  */
sealed trait IncomingMessage

/**
  * client request for user permissions specifying which fields can be edited for given user credentials
  */
case class RequestUserPermissions (uid: String, pw: Array[Byte]) extends IncomingMessage

/**
  * client report of changed (edited) Provider field values
  */
case class ProviderChange (uid: String, pw: Array[Byte], date: DateTime, provider: String, fieldValues: Seq[(String,FieldValue)]) extends IncomingMessage


/**
  * JSON parser for incoming (client) messages
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