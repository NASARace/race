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
import gov.nasa.race.common.{JsonParseException, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.{immutable, mutable}
import scala.collection.immutable.ListMap
import scala.concurrent.duration.Duration

/**
  * class representing the *description* of a provider (field owner)
  */
case class Provider (id: String, info: String, update: Duration) {

  def serializeTo (w: JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember("id", id)
      w.writeStringMember("info", info)
      w.writeLongMember("update", update.toMillis)
    }
  }
}


/**
  * class representing a versioned, named and ordered collection of Provider specs
  */
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

object ProviderCatalogParser {
  //--- lexical constants
  private val _title_     = asc("title")
  private val _rev_ = asc("rev")
  private val _providers_ = asc("providers")
  private val _id_ = asc("id")
  private val _info_ = asc("info")
  private val _update_ = asc("update")
}

/**
  * parser for provider specs
  */
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

/**
  * instance type of a provider that holds field values
  */
case class ProviderData (id: String, rev: Int, date: DateTime, fieldValues: immutable.Map[String,FieldValue])

/**
  * parser for provider data
  */
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