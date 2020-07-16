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
import gov.nasa.race.common.{CharSeqByteSlice, JsonParseException, JsonWriter, UTF8JsonPullParser}

import scala.collection.immutable.ListMap

/**
  * the root type for field instances
  */
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

/**
  * field instantiation factory interface
  */
sealed trait FieldType {
  def instantiate (id: String, info: String, header: Option[String]): Field
  def typeName: String
}

//--- Long fields

object LongField extends FieldType {
  def instantiate (id: String, info: String, header: Option[String]): Field = LongField(id,info,header)
  def typeName: String = "integer"
}

/**
  * Field implementation for Long values
  */
case class LongField (id: String, info: String, header: Option[String]) extends Field {
  def valueFrom (bs: CharSeqByteSlice) = LongValue(bs.toLong)
  def typeName = LongField.typeName
}

//--- Double fields

object DoubleField extends FieldType {
  def instantiate (id: String, info: String, header: Option[String]): Field = DoubleField(id,info,header)
  def typeName: String = "rational"
}

/**
  * Field implementation for Double values
  */
case class DoubleField (id: String, info: String, header: Option[String]) extends Field {
  def valueFrom (bs: CharSeqByteSlice) = DoubleValue(bs.toDouble)
  def typeName = DoubleField.typeName
}

//--- catalog

/**
  * versioned, named and ordered list of field specs
  */
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

/**
  * JSON parser for FieldCatalogs
  */
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


