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
import gov.nasa.race.common.{CharSeqByteSlice, JsonParseException, JsonSerializable, JsonWriter, UTF8JsonPullParser, Utf8Slice}
import gov.nasa.race.uom.{DateTime, Time}

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

case class FieldFormula (src: String, val evalSite: Option[Regex], val evalTime: Option[Time]) {
  private var fexpr: FieldExpression = UndefinedFieldExpression
  private var dependencies: Set[String] = Set.empty[String]

  def compileWith (parser: FieldExpressionParser): Unit = {
    parser.parseAll(parser.expr, src) match {
      case parser.Success(fe: FieldExpression,_) =>
        fexpr = fe
        dependencies = fe.dependencies(Set.empty[String])
      case parser.Failure(msg,_) => throw new RuntimeException(s"formula '$src' failed to compile: $msg")
      case parser.Error(msg,_) => throw new RuntimeException(s"error compiling formula '$src': $msg")
    }
  }

  def evalWith (ctx: EvalContext): FieldValue = fexpr.eval(ctx)

  def hasDependencies: Boolean = dependencies.nonEmpty
  def containsDependency (dep: String): Boolean = dependencies.contains(dep)

  def getDependencies: Set[String] = dependencies
}

/**
  * the root type for field instances
  */
sealed trait Field extends JsonSerializable {
  val id: String
  val info: String

  val attrs: Seq[String]  // extensible set of boolean options
  val min: Option[FieldValue]
  val max: Option[FieldValue]

  val formula: Option[FieldFormula]

  val isLocked: Boolean = attrs.contains("locked") // if set the field can only be updated through formula eval

  def valueToString (fv: FieldValue): String
  def valueFrom(bs: CharSeqByteSlice)(implicit date: DateTime): FieldValue
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
      ifSome(formula) { ff=>
        w.writeStringMember("formula", ff.src)
      }
    }
  }

  def compileWith (parser: FieldExpressionParser): Unit = formula.foreach(_.compileWith(parser))
  def evalWith (ctx: EvalContext): Option[FieldValue] = formula.map(_.evalWith(ctx))

  def hasDependencies: Boolean = formula.isDefined && formula.get.hasDependencies
  def containsDependency (dep: String): Boolean = formula.isDefined && formula.get.containsDependency(dep)

  def hasAllDependencies (map: Map[String,FieldValue]): Boolean = {
    formula match {
      case Some(f) =>
        f.getDependencies.foreach { fid=>
          if (!map.contains(fid)) return false
        }
        true
      case None => true
    }
  }

  def containsAnyDependency (list: scala.collection.Seq[(String,FieldValue)]): Boolean = {
    formula match {
      case Some(f) => list.exists( e=> f.containsDependency(e._1))
      case None => true
    }
  }
}

/**
  * field instantiation factory interface
  */
sealed trait FieldType {
  def instantiate (id: String, info: String,
                   attrs: Seq[String] = Seq.empty,
                   min: Option[FieldValue] = None,
                   max: Option[FieldValue] = None,
                   formula: Option[FieldFormula] = None): Field
  def typeName: String
}

//--- Long fields

object LongField extends FieldType {
  def instantiate (id: String, info: String,
                   attrs: Seq[String], min: Option[FieldValue], max: Option[FieldValue],
                   formula: Option[FieldFormula]): Field = LongField(id,info,attrs,min,max,formula)
  def typeName: String = "integer"
}

/**
  * Field implementation for Long values
  */
case class LongField (id: String, info: String, attrs: Seq[String],
                      min: Option[FieldValue], max: Option[FieldValue],
                      formula: Option[FieldFormula]) extends Field {
  def valueFrom (bs: CharSeqByteSlice)(implicit date: DateTime) = LongValue(bs.toLong)
  def typeName = LongField.typeName
  def valueToString (fv: FieldValue): String = fv.toLong.toString
}

//--- LongArray

//--- Double fields

object DoubleField extends FieldType {
  def instantiate (id: String, info: String, attrs: Seq[String],
                   min: Option[FieldValue], max: Option[FieldValue],
                   formula: Option[FieldFormula]): Field = DoubleField(id,info,attrs,min,max,formula)
  def typeName: String = "rational"
}

/**
  * Field implementation for Double values
  */
case class DoubleField (id: String, info: String, attrs: Seq[String], min: Option[FieldValue], max: Option[FieldValue],
                        formula: Option[FieldFormula]) extends Field {
  def valueFrom (bs: CharSeqByteSlice)(implicit date: DateTime) = DoubleValue(bs.toDouble)
  def typeName = DoubleField.typeName
  def valueToString (fv: FieldValue): String = fv.toDouble.toString
}


//--- DoubleArray


//--- field catalog

/**
  * versioned, named and ordered list of field specs
  */
case class FieldCatalog (id: String, info: String, date: DateTime, fields: ListMap[String,Field]) extends JsonSerializable {

  def serializeTo (w: JsonWriter): Unit = {
    w.clear.writeObject( _
      .writeMemberObject("fieldCatalog") { _
        .writeStringMember("id", id)
        .writeStringMember("info", info)
        .writeDateTimeMember("date", date)
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

  def orderedEntries[T] (map: collection.Map[String,T]): Seq[(String,T)] =  {
    fields.foldLeft(Seq.empty[(String,T)]) { (acc,e) =>
      map.get(e._1) match {
        case Some(t) => acc :+ (e._1, t)
        case None => acc // provider not in map
      }
    }
  }
}

object FieldCatalogParser {
  //--- lexical constants
  private val _id_        = asc("id")
  private val _info_      = asc("info")
  private val _date_       = asc("date")
  private val _fields_    = asc("fields")
  private val _type_      = asc("type")
  private val _integer_   = asc(LongField.typeName)
  private val _rational_  = asc(DoubleField.typeName)
  private val _attrs_     = asc("attrs")
  private val _min_       = asc("min")
  private val _max_       = asc("max")
  private val _formula_   = asc("formula")
  private val _src_       = asc("src")
  private val _evalsite_  = asc("evalsite")
  private val _evaltime_  = asc("evaltime")
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
    implicit val date = DateTime.UndefinedDateTime  // limit values are constants
    if (s.isRationalNumber) {
      DoubleValue(s.toDouble)
    } else {
      LongValue(s.toLong)
    }
  }

  def parse (buf: Array[Byte]): Option[FieldCatalog] = {
    initialize(buf)
    try {
      readNextObject {
        val catalogId = readQuotedMember(_id_).toString
        val catalogInfo = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

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

            val formula = readOptionalObjectMember(_formula_) {
              val src = readQuotedMember(_src_).toString // we do need at least a source
              val evalSite = readOptionalQuotedMember(_evalsite_).map( v=> new Regex(v.toString))
              val evalTime = readOptionalQuotedMember(_evaltime_).map( v=> Time.parseHHmmss(v.toString))
              FieldFormula(src,evalSite,evalTime)
            }

            fieldType.instantiate(id,info,attrs,min,max,formula)
          }
          fields = fields + (field.id -> field)
        }
        Some(FieldCatalog( catalogId, catalogInfo, date, fields))
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed fieldCatalog: ${x.getMessage}")
        None
    }
  }
}


