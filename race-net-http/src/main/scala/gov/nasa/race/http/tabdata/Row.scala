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

import java.nio.file.Path

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{CharSeqByteSlice, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, UTF8JsonPullParser, UnixPath}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

object Row {
  //--- lexical constants
  val _id_        = asc("id")
  val _info_      = asc("info")
  val _type_      = asc("type")
  val _integer_   = asc(LongRow.typeName)
  val _integerList_ = asc(LongListRow.typeName)
  val _rational_  = asc(DoubleRow.typeName)
  val _boolean_   = asc(BooleanRow.typeName)
  val _string_    = asc(StringRow.typeName)
  val _header_    = asc(HeaderRow.typeName)
  val _attrs_     = asc("attrs")
}

/**
  * the named and typed elements associated to a Column (tabdata cell variable definitions)
  *
  * TODO - what is the value of keeping the cell type as type parameter?
  */
sealed trait Row[+T <: CellValue] extends JsonSerializable with CellTyped with PathObject {
  import Row._

  val id: Path
  val info: String
  val attrs: Seq[String]  // extensible set of boolean options

  val isLocked: Boolean = attrs.contains("locked") // if set the field can only be updated through formula eval

  def valueToString (fv: CellValue): String
  def typeName: String
  def undefinedCellValue: CellValue

  def resolve (p: Path): Path = id.resolve(p)
  def createRef (col: Path): CellRef[T]

  def serializeTo (w:JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember(_id_, id.toString)
      w.writeStringMember(_info_, info)
      w.writeStringMember(_type_, typeName)

      // optional parts required by the client
      if (attrs.nonEmpty) w.writeStringArrayMember(_attrs_, attrs)
    }
  }

  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): T // this is not for parsing Rows but for parsing ColumnData
}

trait RowParser extends JsonPullParser {
  import Row._

  def parseRow (listId: Path): AnyRow = {
    def readRowType(): RowFactory = {
      val v = readQuotedMember(_type_)
      if (v == _integer_) LongRow
      else if (v == _rational_) DoubleRow
      else if (v == _boolean_) BooleanRow
      else if (v == _string_) StringRow
      else if (v == _header_) HeaderRow
      else if (v == _integerList_) LongListRow
      else throw exception(s"unknown field type: $v")
    }

    val id = listId.resolve(UnixPath.intern(readQuotedMember(_id_)))
    val rowFactory = readRowType()
    val info = readQuotedMember(_info_).toString
    val attrs = readOptionalStringArrayMemberInto(_attrs_,ArrayBuffer.empty[String]).map(_.toSeq).getOrElse(Seq.empty[String])

    rowFactory.instantiate(id,info,attrs)
  }
}

trait NumRow [+T <: NumCellValue] extends Row[T]

/**
  * field instantiation factory interface
  */
sealed trait RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String] = Seq.empty): AnyRow
  def typeName: String
}

//--- Long rows

object LongRow extends RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String]): LongRow = LongRow(id,info,attrs)
  def typeName: String = "integer"
}

/**
  * Field implementation for Long values
  */
case class LongRow(id: Path, info: String, attrs: Seq[String] = Seq.empty) extends NumRow[LongCellValue] {
  val cellType = classOf[LongCellValue]

  def typeName = LongRow.typeName
  def valueToString (fv: CellValue): String = fv.valueToString
  def undefinedCellValue: CellValue = UndefinedLongCellValue
  def createRef (col: Path): CellRef[LongCellValue] = LongCellRef(col,id)
  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): LongCellValue = LongCellValue.parseFrom(p)(date)
}


//--- Double rows

object DoubleRow extends RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String]): DoubleRow = DoubleRow(id,info,attrs)
  def typeName: String = "rational"
}

/**
  * Field implementation for Double values
  */
case class DoubleRow(id: Path, info: String, attrs: Seq[String] = Seq.empty) extends NumRow[DoubleCellValue] {
  val cellType = classOf[DoubleCellValue]

  def typeName = DoubleRow.typeName
  def valueToString (fv: CellValue): String = fv.valueToString
  def undefinedCellValue: CellValue = UndefinedDoubleCellValue
  def createRef (col: Path): CellRef[DoubleCellValue] = DoubleCellRef(col,id)
  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): DoubleCellValue = DoubleCellValue.parseFrom(p)(date)
}

//--- Boolean rows

object BooleanRow extends RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String]): BooleanRow = BooleanRow(id,info,attrs)
  def typeName: String = "boolean"
}

case class BooleanRow(id: Path, info: String, attrs: Seq[String] = Seq.empty) extends Row[BooleanCellValue] {
  val cellType = classOf[BooleanCellValue]

  def typeName = BooleanRow.typeName
  def valueToString (fv: CellValue): String = fv.valueToString
  def undefinedCellValue: CellValue = UndefinedBooleanCellValue
  def createRef (col: Path): CellRef[BooleanCellValue] = BooleanCellRef(col,id)
  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): BooleanCellValue = BooleanCellValue.parseFrom(p)(date)
}

//--- String rows

object StringRow extends RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String]): StringRow = StringRow(id,info,attrs)
  def typeName: String = "string"
}

case class StringRow(id: Path, info: String, attrs: Seq[String] = Seq.empty) extends Row[StringCellValue] {
  val cellType = classOf[StringCellValue]

  def typeName = StringRow.typeName
  def valueToString (fv: CellValue): String = fv.valueToString
  def undefinedCellValue: CellValue = UndefinedStringCellValue
  def createRef (col: Path): CellRef[StringCellValue] = StringCellRef(col,id)
  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): StringCellValue = StringCellValue.parseFrom(p)(date)
}

//--- a non-editable (empty) String row

// TODO - maybe we should throw an exception if somebody tries to set to retrieve HeaderCells

object HeaderRow extends RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String]): HeaderRow = HeaderRow(id,info,attrs)
  def typeName: String = "header"
}

case class HeaderRow (id: Path, info: String, attrs: Seq[String] = Seq.empty) extends Row[StringCellValue] {
  val cellType = classOf[StringCellValue]

  def typeName = HeaderRow.typeName
  def valueToString (fv: CellValue): String = "" // always empty
  def undefinedCellValue: CellValue = UndefinedStringCellValue
  def createRef (col: Path): CellRef[StringCellValue] = StringCellRef(col,id)
  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): StringCellValue = StringCellValue.parseFrom(p)(date)
}

//--- LongList row

object LongListRow extends RowFactory {
  def instantiate (id: Path, info: String, attrs: Seq[String]): LongListRow = LongListRow(id,info,attrs)
  def typeName: String = "integer[]"
}

case class LongListRow (id: Path, info: String, attrs: Seq[String] = Seq.empty) extends Row[LongListCellValue] {
  val cellType = classOf[LongListCellValue]

  def typeName = LongListRow.typeName
  def valueToString (fv: CellValue): String = fv.toString
  def undefinedCellValue: CellValue = UndefinedLongListCellValue
  def createRef (col: Path): CellRef[LongListCellValue] = LongListCellRef(col,id)
  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): LongListCellValue = LongListCellValue.parseFrom(p)(date)
}

//--- field catalog

object RowList {
  //--- lexical constants
  val _rowList_   = asc("rowList")
  val _id_        = asc("id")
  val _info_      = asc("info")
  val _date_      = asc("date")
  val _rows_      = asc("rows")
}

/**
  * versioned, named and ordered list of field specs
  */
case class RowList (id: Path, info: String, date: DateTime, rows: ListMap[Path,AnyRow]) extends JsonSerializable {
  import RowList._

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject( _
      .writeMemberObject(_rowList_) { _
        .writeStringMember(_id_, id.toString)
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

  //--- (some) list/map forwarders
  def size: Int = rows.size
  def apply (p: Path): AnyRow = rows(p)
  def get (p: Path): Option[AnyRow] = rows.get(p)
  def contains (p: Path): Boolean = rows.contains(p)
  def foreach[U] (f: ((Path,AnyRow))=>U): Unit = rows.foreach(f)

  def orderedEntries[T] (map: collection.Map[Path,T]): Seq[(Path,T)] =  {
    rows.foldLeft(Seq.empty[(Path,T)]) { (acc, e) =>
      map.get(e._1) match {
        case Some(t) => acc :+ (e._1, t)
        case None => acc // provider not in map
      }
    }
  }

  def getMatchingRows(rowSpec: String): Seq[AnyRow] = {
    if (UnixPath.isPattern(rowSpec)) {
      val pm = UnixPath.matcher(rowSpec)
      rows.foldLeft(ArrayBuffer.empty[AnyRow]){ (acc,e) =>
        if (pm.matches(e._1)) acc += e._2 else acc
      }.toSeq

    } else {
      val p = UnixPath.intern(rowSpec)
      rows.get(p).toList
    }
  }

  def resolvePathSpec (rowSpec: String): String = {
    if (UnixPath.isAbsolutePathSpec(rowSpec)) {
      rowSpec
    } else {
      id.toString + '/' + rowSpec
    }
  }
}


/**
  * JSON parser for FieldCatalogs
  */
class RowListParser extends UTF8JsonPullParser with RowParser {
  import RowList._

  def parse (buf: Array[Byte]): Option[RowList] = {
    initialize(buf)
    try {
      readNextObject {
        val id = UnixPath.intern(readQuotedMember(_id_))
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        var rows = ListMap.empty[Path,AnyRow]
        foreachInNextArrayMember(_rows_) {
          val row = readNextObject(parseRow(id))
          rows = rows + (row.id -> row)
        }
        Some(RowList( id, info, date, rows))
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed rowList: ${x.getMessage}")
        None
    }
  }
}


