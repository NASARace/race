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


import gov.nasa.race.{Failure, ResultValue, SuccessValue}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{Glob, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer

object RowConst {
  //--- lexical constants
  val _type_        = asc("type")
  val _id_          = asc("id")
  val _info_        = asc("info")
  val _integer_     = asc("integer")
  val _real_        = asc("real")
  val _integerList_ = asc("integerList")
  val _realList_    = asc("realList")
  val _boolean_     = asc("boolean")
  val _string_      = asc("string")
  val _header_      = asc("header")
  val _attrs_       = asc("attrs")
}
import RowConst._

import scala.collection.{SeqMap, immutable}
import scala.reflect.{ClassTag, classTag}

object Row {
  def apply (id: String)(rowType: String, info: String = s"this is row $id", updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty): Row[_] = {
    rowType match {
        // builtin types
      case "integer" => IntegerRow(id,info,updateFilter,attrs)
      case "real" => RealRow(id,info,updateFilter,attrs)
      case "boolean" => BoolRow(id,info,updateFilter,attrs)
      case "string" => StringRow(id,info,updateFilter,attrs)
      case "integerList" => IntegerListRow(id,info,updateFilter,attrs)
      case "realList" => RealListRow(id,info,updateFilter,attrs)
        // ..we might add an extensible registry for user defined types here (akin to reference types)
    }
  }
}

/**
  * abstraction of rows
  * mostly holds the cell value type and acts as a factory for objects that depend on this type
  */
abstract class Row[T: ClassTag] extends JsonSerializable {

  def cellType: Class[_] = classTag[T].runtimeClass
  val typeName: String

  val id: String
  val info: String
  val updateFilter: UpdateFilter
  val attrs: Seq[String]  // extensible set of symbolic attributes

  val isLocked: Boolean = attrs.contains("locked") // if set the field can only be updated through formula eval

  //--- constructor methods for associated types
  def undefinedCellValue: CellValue[T]
  def cellRef (colId: String): CellRef[T]
  def formula (src: String, ce: CellExpression[_]): ResultValue[CellFormula[_]]

  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): CellValue[T] // this is not for parsing Rows but for parsing ColumnData values

  def serializeTo (w:JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember(_id_, id)
      w.writeStringMember(_info_, info)
      w.writeStringMember(_type_, typeName)

      if (attrs.nonEmpty) w.writeStringArrayMember(_attrs_, attrs)
    }
  }
}

/**
  * mixin for a JsonPullParser to read Rows
  */
trait RowParser extends JsonPullParser with UpdateFilterParser {
  import RowConst._

  def parseRow (rowListId: String, columnListId: String): Row[_] = {
    val id = PathIdentifier.resolve(readQuotedMember(_id_),rowListId)
    val rowType = readQuotedMember(_type_).toString
    val info = readQuotedMember(_info_).toString
    val updateFilter = parseUpdateFilter(columnListId,UpdateFilter.sendReceiveAll)
    val attrs = readOptionalStringArrayMemberInto(_attrs_,ArrayBuffer.empty[String]).map(_.toSeq).getOrElse(Seq.empty[String])

    Row(id)(rowType,info,updateFilter,attrs)
  }
}

//--- concrete Row types

/**
  * Row holding Integer values (mapping to Long)
  */
case class IntegerRow(id: String, info: String, updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty) extends Row[Long] {
  val typeName = _integer_.toString

  override def undefinedCellValue: CellValue[Long] = UndefinedIntegerCellValue
  override def cellRef (colId: String): IntegerCellRef = IntegerCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[IntegerCellFormula] = {
    ce match {
      case intExpr: IntegerExpression => SuccessValue(IntegerCellFormula(src,intExpr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = IntegerCellValue.parseValueFrom(p,date)
}

/**
  * Row holding Real values (mapping to Double)
  */
case class RealRow (id: String, info: String, updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty) extends Row[Double] {
  val typeName = _real_.toString

  override def undefinedCellValue: CellValue[Double] = UndefinedRealCellValue
  override def cellRef (colId: String): RealCellRef = RealCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[RealCellFormula] = {
    ce match {
      case expr: RealExpression => SuccessValue(RealCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = RealCellValue.parseValueFrom(p,date)
}

/**
  * Row holding Boolean values
  */
case class BoolRow (id: String, info: String, updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty) extends Row[Boolean] {
  val typeName = _boolean_.toString

  override def undefinedCellValue: CellValue[Boolean] = UndefinedBoolCellValue
  override def cellRef (colId: String): BoolCellRef = BoolCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[BoolCellFormula] = {
    ce match {
      case boolExpr: BoolExpression => SuccessValue(BoolCellFormula(src,boolExpr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = BoolCellValue.parseValueFrom(p,date)
}

/**
  * Row holding String values
  */
case class StringRow (id: String, info: String, updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty) extends Row[String] {
  val typeName = _string_.toString

  override def undefinedCellValue: CellValue[String] = UndefinedStringCellValue
  override def cellRef (colId: String): StringCellRef = StringCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[StringCellFormula] = {
    ce match {
      case strExpr: StringExpression => SuccessValue(StringCellFormula(src,strExpr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = StringCellValue.parseValueFrom(p,date)
}

/**
  * Row holding IntegerList values
  */
case class IntegerListRow (id: String, info: String, updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty) extends Row[IntegerList] {
  val typeName = _integerList_.toString

  override def undefinedCellValue: CellValue[IntegerList] = UndefinedIntegerListCellValue
  override def cellRef (colId: String): IntegerListCellRef = IntegerListCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[IntegerListCellFormula] = {
    ce match {
      case intListExpr: IntegerListExpression => SuccessValue(IntegerListCellFormula(src,intListExpr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = IntegerListCellValue.parseValueFrom(p,date)
}

/**
  * Row holding RealList values
  */
case class RealListRow (id: String, info: String, updateFilter: UpdateFilter = UpdateFilter.localOnly, attrs: Seq[String] = Seq.empty) extends Row[RealList] {
  val typeName = _realList_.toString

  override def undefinedCellValue: CellValue[RealList] = UndefinedRealListCellValue
  override def cellRef (colId: String): RealListCellRef = RealListCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[RealListCellFormula] = {
    ce match {
      case listExpr: RealListExpression => SuccessValue(RealListCellFormula(src,listExpr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = RealListCellValue.parseValueFrom(p,date)
}


object RowList {
  //--- lexical constants
  val _rowList_   = asc("rowList")
  val _id_        = asc("id")
  val _info_      = asc("info")
  val _date_      = asc("date")
  val _rows_      = asc("rows")

  def apply (id: String, date: DateTime, rows: Row[_]*): RowList = new RowList( id, s"this is row list $id", date, SeqMap( rows.map( r=> r.id -> r):_*))
}

/**
  * ordered map of Rows
  */
case class RowList (id: String, info: String, date: DateTime, rows: immutable.SeqMap[String,Row[_]]) extends JsonSerializable {
  import RowList._

  def getUndefinedValue(id: String): Option[CellValue[_]] = rows.get(id).map(_.undefinedCellValue)

  def get (id: String): Option[Row[_]] = rows.get(id)
  def apply (id: String): Row[_] = rows(id)
  def contains (key: String): Boolean = rows.contains(key)

  def foreach (f: Row[_]=>Unit): Unit = rows.foreach( e=> f(e._2))

  def foreachIn[T] (map: Map[String,T])(f: (String,T)=>Unit): Unit = {
    rows.foreach { e=>
      map.get(e._1) match {
        case Some(t) => f(e._1, t)
        case None => // not in map
      }
    }
  }

  def orderedEntries[T] (map: collection.Map[String,T]): Seq[(String,T)] =  {
    rows.foldRight(Seq.empty[(String,T)]) { (e,acc) =>
      map.get(e._1) match {
        case Some(t) => (e._1, t) +: acc
        case None => acc // row not in map
      }
    }
  }

  def matching (globPattern: String): Iterable[Row[_]] = {
    val regex = Glob.resolvedGlob2Regex(globPattern, id)
    rows.foldRight(Seq.empty[Row[_]]){ (e,list) => if (regex.matches(e._1)) e._2 +: list else list }
  }

  def union (other: RowList, newId: String = id): RowList = {
    var newDate = date

    if (other eq this) { // shortcut
      this

    } else {
      if (other.date > newDate) newDate = other.date

      var newRows = rows
      other.rows.foreach { e=>
        val otherRowId = e._1
        val otherRow = e._2

        rows.get(otherRowId) match {
          case Some(row) =>
            if (row.cellType ne otherRow.cellType) throw sys.error(s"incompatible definitions for rows: ${row.id}")
          case None => // we don't have this one, sort it in
            newRows = rows.foldLeft( SeqMap.empty[String,Row[_]]) { (acc,r) =>
              if (r._1.compareTo(otherRowId) > 0) acc + e else acc
            }
            if (newRows.size == rows.size) newRows = newRows + e // append
        }
      }

      if (newId != id) {
        new RowList(newId, info, newDate, newRows)
      } else {
        if (newRows.size == rows.size) this else new RowList(id,info,newDate,newRows)
      }
    }
  }

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject( _
      .writeMemberObject(_rowList_) { _
        .writeStringMember(_id_, id)
        .writeStringMember(_info_, info)
        .writeDateTimeMember(_date_, date)
        .writeArrayMember(_rows_){ w=>
          for (row <- rows.valuesIterator){
            row.serializeTo(w)
          }
        }
      }
    )
  }
}

/**
  * JSON parser for FieldCatalogs
  */
class RowListParser (columnListId: String) extends UTF8JsonPullParser with RowParser {
  import RowList._

  def parse (buf: Array[Byte]): Option[RowList] = {
    initialize(buf)
    try {
      readNextObject {
        val rowListId = readQuotedMember(_id_).toString
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        var rows = SeqMap.empty[String,Row[_]]
        foreachInNextArrayMember(_rows_) {
          val row = readNextObject( parseRow( rowListId,columnListId) )
          rows = rows + (row.id -> row)
        }
        Some(RowList( rowListId, info, date, rows))
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed rowList: ${x.getMessage}")
        None
    }
  }
}

