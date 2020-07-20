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

import gov.nasa.race._
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{CharSeqByteSlice, JsonParseException, JsonWriter, UTF8JsonPullParser, Utf8Slice}

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

/**
  * the root type for field instances
  */
sealed trait Field {
  val id: String
  val info: String

  val attrs: Seq[String]  // extensible set of boolean options
  val min: Option[FieldValue]
  val max: Option[FieldValue]

  //--- those are only internal, i.e. are not sent to the client
  val formula: Option[String] // we have to store the source since we can't compile before we have all fields
  protected var fexpr: Option[FieldExpression] = None // the compiled formula
  protected var dependencies: Set[String] = Set.empty // the fields fexpr depends on (if any)

  val isLocked: Boolean = attrs.contains("locked") // can only be updated through formula

  def valueToString (fv: FieldValue): String
  def valueFrom(bs: CharSeqByteSlice): FieldValue
  def typeName: String
  def hasFormula: Boolean = formula.isDefined

  def serializeTo (w:JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember("id", id)
      w.writeStringMember("info", info)
      w.writeStringMember("type", typeName)

      // optional parts required by the client
      if (attrs.nonEmpty) w.writeStringArrayMember("attrs", attrs)
      ifSome(min) {v=>  w.writeUnQuotedMember("min", valueToString(v)) }
      ifSome(max) {v=>  w.writeUnQuotedMember("max", valueToString(v)) }
      ifSome(formula) {v=> w.writeStringMember("formula", v) }
    }
  }

  def compileWith (parser: FieldExpressionParser): Unit = {
    ifSome(formula){ src=>
      parser.parseAll(parser.expr, src) match {
        case parser.Success(fe: FieldExpression,_) =>
          fexpr = Some(fe)
          dependencies = fe.dependencies(Set.empty[String])
        case parser.Failure(msg,_) => throw new RuntimeException(s"formula of $id failed to compile: $msg")
        case parser.Error(msg,_) => throw new RuntimeException(s"error compiling formula of $id: $msg")
      }
    }
  }

  def hasDependencies: Boolean = dependencies.nonEmpty
  def containsDependency (dep: String): Boolean = dependencies.contains(dep)

  def evalWith (ctx: EvalContext): Option[FieldValue] = fexpr.map(_.eval(ctx))
}

/**
  * field instantiation factory interface
  */
sealed trait FieldType {
  def instantiate (id: String, info: String,
                   attrs: Seq[String] = Seq.empty,
                   min: Option[FieldValue] = None,
                   max: Option[FieldValue] = None,
                   formula: Option[String] = None): Field
  def typeName: String
}

//--- Long fields

object LongField extends FieldType {
  def instantiate (id: String, info: String,
                   attrs: Seq[String], min: Option[FieldValue], max: Option[FieldValue],
                   formula: Option[String]): Field = LongField(id,info,attrs,min,max,formula)
  def typeName: String = "integer"
}

/**
  * Field implementation for Long values
  */
case class LongField (id: String, info: String, attrs: Seq[String], min: Option[FieldValue], max: Option[FieldValue], formula: Option[String]) extends Field {
  def valueFrom (bs: CharSeqByteSlice) = LongValue(bs.toLong)
  def typeName = LongField.typeName
  def valueToString (fv: FieldValue): String = fv.toLong.toString
}

//--- Double fields

object DoubleField extends FieldType {
  def instantiate (id: String, info: String,
                   attrs: Seq[String], min: Option[FieldValue], max: Option[FieldValue],
                   formula: Option[String]): Field = DoubleField(id,info,attrs,min,max,formula)
  def typeName: String = "rational"
}

/**
  * Field implementation for Double values
  */
case class DoubleField (id: String, info: String, attrs: Seq[String], min: Option[FieldValue], max: Option[FieldValue], formula: Option[String]) extends Field {
  def valueFrom (bs: CharSeqByteSlice) = DoubleValue(bs.toDouble)
  def typeName = DoubleField.typeName
  def valueToString (fv: FieldValue): String = fv.toDouble.toString
}

//--- field catalog

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

  def compileWithFunctions (functions: Map[String,FunFactory]): Unit = {
    val parser = new FieldExpressionParser(fields,functions)

    fields.foreach { fe=>
      val field = fe._2
      field.compileWith(parser)
    }
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
  private val _attrs_     = asc("attrs")
  private val _min_       = asc("min")
  private val _max_       = asc("max")
  private val _formula_   = asc("formula")
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

  private def readLimitValue (s: Utf8Slice): FieldValue = {
    if (s.isRationalNumber) DoubleValue(s.toDouble) else LongValue(s.toLong)
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

            //--- these are all optional
            val attrs = readOptionalStringArrayMemberInto(_attrs_,ArrayBuffer.empty[String]).map(_.toSeq).getOrElse(Seq.empty[String])
            val min = readOptionalUnQuotedMember(_min_).map(readLimitValue)
            val max = readOptionalUnQuotedMember(_max_).map(readLimitValue)
            val formula = readOptionalQuotedMember(_formula_).map(_.toString)

            fieldType.instantiate(id,info,attrs,min,max,formula)
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


