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
package gov.nasa.race.share

import gov.nasa.race.{Failure, ResultValue, SuccessValue}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{CharSeqByteSlice, Glob, Internalizer, JsonMessageObject, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.share.NodeMatcher.{allMatcher, noneMatcher}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.collection.immutable.SeqMap
import scala.collection.mutable
import scala.reflect.{ClassTag, classTag}

object Row extends JsonConstants {
  //--- lexical constants
  val T_integer = "integer"
  val T_real = "real"
  val T_boolean = "boolean"
  val T_string = "string"
  val T_link = "link"
  val T_integerList = "integerList"
  val T_realList = "realList"

  val TYPE = asc("type")
  val INTEGER = asc(T_integer)
  val REAL = asc(T_real)
  val BOOLEAN = asc(T_boolean)
  val STRING = asc(T_string)
  val LINK = asc(T_link)
  val INTEGER_LIST = asc(T_integerList)
  val REAL_LIST = asc(T_realList)

  def apply (id: String)(rowType: String,
                         info: String = s"this is row $id",
                         send: NodeMatcher = allMatcher,
                         receive: NodeMatcher = allMatcher,
                         attrs: Seq[String] = Seq.empty,
                         values: Seq[String] = Seq.empty): Row[_] = {
    rowType match {
        // builtin types
      case T_integer => IntegerRow(id,info,send,receive,attrs,parseIntegerValues(values))
      case T_real => RealRow(id,info,send,receive,attrs,parseRealValues(values))
      case T_boolean => BoolRow(id,info,send,receive,attrs)
      case T_string => StringRow(id,info,send,receive,attrs,values)
      case T_link => LinkRow(id,info,send,receive,attrs)
      case T_integerList => IntegerListRow(id,info,send,receive,attrs)
      case T_realList => RealListRow(id,info,send,receive,attrs)
        // ..we might add an extensible registry for user defined types here (akin to reference types)
      case other => throw new IllegalArgumentException(s"unknown row type: $other")
    }
  }

  def parseIntegerValues (specs: Seq[String]): Seq[Long] = specs.map(java.lang.Long.parseLong)
  def parseRealValues (specs: Seq[String]): Seq[Double] = specs.map(java.lang.Double.parseDouble)


  // mostly for testing
  def integerRow(id: String ): Row[_] = Row(id)(T_integer)
  def realRow(id: String ): Row[_] = Row(id)(T_real)
  def booleanRow(id: String ): Row[_] = Row(id)(T_boolean)
  def stringRow(id: String ): Row[_] = Row(id)(T_string)
  def linkRow(id: String): Row[_] = Row(id)(T_link)
  def integerListRow(id: String): Row[_] = Row(id)(T_integerList)
  def realListRow(id: String): Row[_] = Row(id)(T_realList)

  // this is on top of respective Column send/receive matchers so default can be allMatcher
  val defaultSendMatcher = allMatcher
  val defaultReceiveMatcher = allMatcher
}
import Row._


/**
  * abstraction of rows
  * mostly holds the cell value type and acts as a factory for objects that depend on this type
  *
  * note this can't be a trait in Scala 2 since we need the ClassTag on the type parameter to access its runtime class
  */
abstract class Row[T: ClassTag] extends JsonSerializable {

  def cellType: Class[_] = classTag[T].runtimeClass
  val typeName: String

  val id: String
  val info: String
  val send: NodeMatcher
  val receive: NodeMatcher
  val attrs: Seq[String]  // extensible set of symbolic attributes

  val isLocked: Boolean = attrs.contains("locked") // if set the field can only be updated through formula eval

  //--- constructor methods for associated types
  def undefinedCellValue: CellValue[T]
  def cellRef (colId: String): CellRef[T]
  def formula (src: String, ce: CellExpression[_]): ResultValue[CellFormula[_]]

  def parseValueFrom (p: JsonPullParser)(implicit date: DateTime): CellValue[T] // this is not for parsing Rows but for parsing ColumnData values

  def serializeValuesTo (w: JsonWriter): Unit = {} // override in concrete Row types that support value sets

  def serializeMembersTo (w:JsonWriter): Unit = {
    w.writeStringMember(ID, id)
    w.writeStringMember(INFO, info)
    w.writeStringMember(TYPE, typeName)
    if (receive != defaultReceiveMatcher) w.writeStringMember(RECEIVE, receive.pattern)
    if (send != defaultSendMatcher) w.writeStringMember(SEND, send.pattern)
    if (attrs.nonEmpty) w.writeStringArrayMember(ATTRS, attrs)
    serializeValuesTo(w)
  }

  // serialize without send/receive info
  def shortSerializeTo (w: JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember(ID, id)
      w.writeStringMember(INFO, info)
      w.writeStringMember(TYPE, typeName)
      if (attrs.nonEmpty) w.writeStringArrayMember(ATTRS, attrs)
      serializeValuesTo(w)
    }
  }

  def isSentTo (nodeId: String)(implicit node: Node, col: Column): Boolean = send.matches(nodeId)(node,Some(col.owner))
  def isReceivedFrom (nodeId: String)(implicit node: Node, col: Column): Boolean = receive.matches(nodeId)(node,Some(col.owner))
}

/**
  * mixin for a JsonPullParser to read Rows
  */
trait RowParser extends JsonPullParser with NodeMatcherParser with AttrsParser {
  import Row._

  def readValues(): Seq[String] = {
    val buf = mutable.Buffer.empty[String]
    foreachElementInCurrentArray {
      if (isScalarValue) {
        buf += value.toString
      } else exception(s"non-scalar value enumeration")
    }
    buf.toSeq
  }

  def readRow(nodeId: String): Row[_] = {  // TODO - do we need list ids ??
    var id: String = null
    var info: String = ""
    var rowType: String = null
    var send: NodeMatcher = defaultSendMatcher
    var receive: NodeMatcher = defaultReceiveMatcher  // 'target' doesn't make sense here, we don't have a suitable context
    var attrs = Seq.empty[String]
    var values = Seq.empty[String]

    foreachMemberInCurrentObject {
      case ID => id = quotedValue.intern
      case INFO => info = quotedValue.toString
      case TYPE => rowType = quotedValue.toString
      case SEND => send = readNodeMatcher(quotedValue,nodeId)
      case RECEIVE => receive = readNodeMatcher(quotedValue,nodeId)
      case ATTRS => attrs = readAttrs()
      case VALUES => values = readValues() // we have to report values as a string collection since we can't rely on having a type yet
    }

    if (id == null) throw exception("missing 'id' in row spec")
    Row(id)(rowType,info,send,receive,attrs,values)
  }
}

//--- concrete Row types
/**
  * Row holding Integer values (mapping to Long)
  */
case class IntegerRow(id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String], values: Seq[Long]) extends Row[Long] {
  val typeName = Row.T_integer

  override def undefinedCellValue: CellValue[Long] = UndefinedIntegerCellValue
  override def cellRef (colId: String): IntegerCellRef = IntegerCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[IntegerCellFormula] = {
    ce match {
      case expr: IntegerExpression => SuccessValue(IntegerCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = IntegerCellValue.readCellValue(p,date)
  override def serializeValuesTo (w: JsonWriter): Unit = if (values.nonEmpty) w.writeLongArrayMember(VALUES,values)
}

/**
  * Row holding Real values (mapping to Double)
  */
case class RealRow (id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String], values: Seq[Double]) extends Row[Double] {
  val typeName = Row.T_real

  override def undefinedCellValue: CellValue[Double] = UndefinedRealCellValue
  override def cellRef (colId: String): RealCellRef = RealCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[RealCellFormula] = {
    ce match {
      case expr: RealExpression => SuccessValue(RealCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = RealCellValue.readCellValue(p,date)
  override def serializeValuesTo (w: JsonWriter): Unit = if (values.nonEmpty) w.writeDoubleArrayMember(VALUES,values)
}

/**
  * Row holding Boolean values
  */
case class BoolRow (id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String]) extends Row[Boolean] {
  val typeName = Row.T_boolean

  override def undefinedCellValue: CellValue[Boolean] = UndefinedBoolCellValue
  override def cellRef (colId: String): BoolCellRef = BoolCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[BoolCellFormula] = {
    ce match {
      case expr: BoolExpression => SuccessValue(BoolCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = BoolCellValue.readCellValue(p,date)
}

/**
  * Row holding String values
  */
case class StringRow (id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String], values: Seq[String]) extends Row[String] {
  val typeName = Row.T_string

  override def undefinedCellValue: CellValue[String] = UndefinedStringCellValue
  override def cellRef (colId: String): StringCellRef = StringCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[StringCellFormula] = {
    ce match {
      case expr: StringExpression => SuccessValue(StringCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = StringCellValue.readCellValue(p,date)
  override def serializeValuesTo (w: JsonWriter): Unit = if (values.nonEmpty) w.writeStringArrayMember(VALUES,values)
}

/**
  * Row holding HTML link value
  */
case class LinkRow (id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String]) extends Row[String] {
  val typeName = Row.T_link

  override def undefinedCellValue: CellValue[String] = UndefinedLinkCellValue
  override def cellRef (colId: String): LinkCellRef = LinkCellRef(colId, id)

  // TODO - should we allow
  override def formula (src: String, ce: CellExpression[_]): ResultValue[LinkCellFormula] = {
    ce match {
      case expr: LinkExpression => SuccessValue(LinkCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = LinkCellValue.readCellValue(p,date)
}

/**
  * Row holding IntegerList values
  */
case class IntegerListRow (id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String]) extends Row[IntegerList] {
  val typeName = Row.T_integerList

  override def undefinedCellValue: CellValue[IntegerList] = UndefinedIntegerListCellValue
  override def cellRef (colId: String): IntegerListCellRef = IntegerListCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[IntegerListCellFormula] = {
    ce match {
      case expr: IntegerListExpression => SuccessValue(IntegerListCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = IntegerListCellValue.readCellValue(p,date)
}

/**
  * Row holding RealList values
  */
case class RealListRow (id: String, info: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String]) extends Row[RealList] {
  val typeName = Row.T_realList

  override def undefinedCellValue: CellValue[RealList] = UndefinedRealListCellValue
  override def cellRef (colId: String): RealListCellRef = RealListCellRef(colId, id)

  override def formula (src: String, ce: CellExpression[_]): ResultValue[RealListCellFormula] = {
    ce match {
      case expr: RealListExpression => SuccessValue(RealListCellFormula(src,expr))
      case other => Failure(s"cell expression of type ${other.getClass.getSimpleName} not compatible with ${getClass.getSimpleName}")
    }
  }

  override def parseValueFrom (p: JsonPullParser)(implicit date: DateTime) = RealListCellValue.readCellValue(p,date)
}


object RowList extends JsonConstants {
  //--- lexical constants
  val ROW_LIST   = asc("rowList")

  def apply (id: String, date: DateTime, rows: Row[_]*): RowList = new RowList( id, s"this is row list $id", date, SeqMap( rows.map( r=> r.id -> r):_*))
}

/**
  * class representing a versioned, named and ordered collection of Row specs
  */
case class RowList (id: String, info: String, date: DateTime, rows: SeqMap[String,Row[_]]) extends JsonMessageObject {
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

  def _serializeMembersTo (w: JsonWriter)(serializeRow: (Row[_],JsonWriter)=>Unit): Unit = {
    w.writeObjectMember(ROW_LIST) { _
      .writeStringMember(ID, id)
      .writeStringMember(INFO, info)
      .writeDateTimeMember(DATE, date)
      .writeArrayMember(ROWS){ w=>
        for (row <- rows.valuesIterator){
          serializeRow(row, w)
        }
      }
    }
  }

  def serializeMembersTo (w: JsonWriter): Unit = _serializeMembersTo(w)( (row,w) => row.serializeTo(w))
  def shortSerializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject { w=>
      _serializeMembersTo(w)( (row,w) => row.shortSerializeTo(w))
    }
  }
}

/**
  * JSON parser for RowLists
  */
class RowListParser (nodeId: String) extends UTF8JsonPullParser with RowParser {
  import RowList._

  def readRows(): SeqMap[String,Row[_]] = {
    var map = SeqMap.empty[String,Row[_]]
    foreachElementInCurrentArray {
      val row = readCurrentObject( readRow(nodeId) )
      map = map + (row.id -> row)
    }
    map
  }

  def readRowList(): RowList = {
    readCurrentObject {
      var id: String = null
      var info: String = null
      var date: DateTime = DateTime.UndefinedDateTime
      var rows = SeqMap.empty[String,Row[_]]

      foreachMemberInCurrentObject {
        case ID => id = quotedValue.toString
        case INFO => info = quotedValue.toString
        case DATE => date = dateTimeValue
        case ROWS => rows = readCurrentArray( readRows() )
      }

      if (id == null) throw exception("missing 'id' in rowList")
      RowList(id,info,date,rows)
    }
  }

  def parse (buf: Array[Byte]): Option[RowList] = {
    initialize(buf)
    try {
      readNextObject {
        Some( readNextObjectMember(ROW_LIST){ readRowList() } )
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed rowList: ${x.getMessage}")
        //x.printStackTrace()
        None
    }
  }
}

