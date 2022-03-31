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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{ConstAsciiSlice, JsonMessageObject, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable


object ColumnData extends JsonConstants {
  //--- lexical constants
  val COLUMN_DATA = asc("columnData")

  // callback to record changed cell values
  type CVRecordFunc = (ColId,Row[_],CellValue[_])=>Unit
  def noRecordFunc(colId: String, row: Row[_], cv: CellValue[_]): Unit = {}

  def apply (id: String, date: DateTime, cvs: (String,CellValue[_])*): ColumnData = {
    val values = cvs.foldLeft( Map.empty[String,CellValue[_]]){ (acc,e)=> acc + (e._1 -> e._2) }
    new ColumnData(id, date, values)
  }

  def cdMap (cds: ColumnData*): Map[String,ColumnData] = {
    cds.foldLeft(Map.empty[String,ColumnData]){ (acc,cd) => acc + (cd.id -> cd) }
  }
}
import gov.nasa.race.share.ColumnData._

/**
  * instance type of a provider that holds the cell values
  *
  * 'date' always has to satisfy the ">= latest cellValue update" property
  *
  * CDs can be used with different RowLists - the dominant requirement is to〖 avoid redundant data storage (both
  * online and serialized) 〗
  */
case class ColumnData (id: String, date: DateTime, values: Map[String,CellValue[_]]) extends JsonMessageObject {

  /**
    * note that apply always returns a value if 'rowId' is a valid RowList key - if 'values' has no 'rowId' entry
    * that is the corresponding undefined value for that row.
    * It is the callers responsibility to check for valid rowId or to handle NoSuchElementExceptions
    */
  def apply (row: Row[_]): CellValue[_] = values.get(row.id) match {
    case Some(cv) => cv
    case None => row.undefinedCellValue
  }

  def get (rowId: String): Option[CellValue[_]] = values.get(rowId)

  def foreach ( f: (String,CellValue[_])=>Unit ) = values.foreach( e=> f(e._1, e._2))

  def foreachOrdered (rowList: RowList)(f: (String,CellValue[_])=>Unit): Unit = {
    rowList.foreach { row=>
      values.get(row.id) match {
        case Some(cv) => f(row.id,cv)
        case None => // ignore
      }
    }
  }

  private def _serializeMembersTo (w: JsonWriter)(valueSerializer: JsonWriter=>Unit): Unit = {
    w.writeObjectMember("columnData") { _
      .writeStringMember(ID, id.toString)
      .writeDateTimeMember(DATE, date)
      .writeObjectMember(ROWS) { w => valueSerializer(w) }
    }
  }

  def serializeMembersTo (w: JsonWriter): Unit = {
    _serializeMembersTo(w){ w=>
      values.foreach { cell =>
        w.writeMemberName(cell._1.toString)
        val wasFormatted = w.format(false) // print this dense no matter what
        cell._2.serializeTo(w)
        w.format(wasFormatted)
      }
    }
  }

  def serializeOrderedTo (w: JsonWriter, rowList: RowList): Unit = {
    w.clear().writeObject { w=>
      _serializeMembersTo(w) { w =>
        rowList.foreach { row=>
          val rid = row.id
          values.get(rid) match {
            case Some(v) =>
              w.writeMemberName(rid.toString)
              val wasFormatted = w.format(false) // print this dense no matter what
              v.serializeTo(w)
              w.format(wasFormatted)
            case None => // ignore, we don't have a value for this row
          }
        }
      }
    }
  }

  /**
    * single value mutator
    *
    * 〖 this has to provide the following update guarantees:
    *    (1) rowId refers to a valid row
    *    (2) this row has the same cellType as the new cellValue
    *    (3) the new cellValue has a later date tag than the current cellValue (if any)
    *    (4) the strict monotone CD date is >= all CV dates 〗
    *
    * success will return a new ColumnData
    * violation of (3) will return the current object
    * violations of (1) and (2) will cause exceptions
    */
  def updateCv (rowList: RowList, rowId: String, cv: CellValue[_], d: DateTime = DateTime.UndefinedDateTime, rec: CVRecordFunc = noRecordFunc): ColumnData = {
    var newDate = DateTime.max(date,d)

    rowList.get(rowId) match {
      case Some(row) =>
        if (row.cellType == cv.cellType) {
          values.get(rowId) match {
            case Some(oldCv) if oldCv.date > cv.date => return if (newDate == date) this else copy(date=newDate) // outdated change, short circuit
            case _ => // new value
          }
          newDate = DateTime.max(newDate,cv.date) // ensure CD date consistency
          val newValues = values + (rowId -> cv)
          rec(id,row,cv)
          ColumnData(id, newDate, newValues)
        } else {
          throw new IllegalArgumentException(s"cellValue of type ${cv.cellType} can't be stored in row '$rowId' of type ${row.cellType}")
        }
      case None => throw new IllegalArgumentException(s"unknown row $rowId")
    }
  }

  /**
    * bulk mutator - this has to ensure the same properties as the single value update
    */
  def updateCvs (rowList: RowList, cvs: Seq[(String,CellValue[_])], d: DateTime = DateTime.UndefinedDateTime, rec: CVRecordFunc = noRecordFunc): ColumnData = {
    var newDate = DateTime.max(date,d)

    val newValues = cvs.foldLeft(values) { (map,e) =>
      val rowId = e._1
      val cv = e._2
      newDate = DateTime.max(newDate,cv.date) // ensure CD date consistency

      rowList.get(rowId) match {
        case Some(row) =>
          if (row.cellType == cv.cellType) {
            values.get(rowId) match {
              case Some(oldCv) if oldCv.date > cv.date => map  // outdated change, short circuit
              case _ =>
                rec(id,row,cv)
                map + (rowId -> cv)
            }
          } else {
            throw new IllegalArgumentException(s"cellValue of type ${cv.cellType} can't be stored in row '$rowId' of type ${row.cellType}")
          }

        case None => throw new IllegalArgumentException(s"unknown row $rowId")
      }
    }

    if (newValues ne values) ColumnData(id,newDate,newValues) else this
  }

  //--- cell value diffs

  def sameIdsAs (other: ColumnData): Boolean = {
    (id == other.id)
  }

  def cellDates: Seq[RowDatePair] = {
    values.foldRight(Seq.empty[RowDatePair]){ (e, acc)=> (e._1 -> e._2.date) +: acc }
  }

  def orderedCellDates (rowList: RowList): Seq[RowDatePair] = {
    rowList.orderedEntries(values).foldRight(Seq.empty[RowDatePair]){ (e, acc)=> (e._1 -> e._2.date) +: acc }
  }

  /**
    * return Seq of cell pairs changed since a reference date
    */
  def changesSince (refDate: DateTime, prioritizeOwn: Boolean): Seq[CellPair] = {
    values.foldRight(Seq.empty[CellPair]) { (e,acc) =>
      val ownCv = e._2
      if (ownCv.date > refDate || (prioritizeOwn && (ownCv.date == refDate))) e +: acc else acc
    }
  }

  def filteredCellPairs (p: (String,CellValue[_])=>Boolean): Seq[CellPair] = {
    values.foldRight(Seq.empty[CellPair]) { (e,acc) => if (p(e._1,e._2)) e +: acc else acc }
  }

  /**
    * return Seq of cell pairs that are newer than or not in other CD
    * it is the callers responsibility to ensure otherCd is comparable (e.g. same id and rowList)
    *
    * note - result not ordered
    */
  def changesSince (otherVersion: ColumnData, prioritizeOwn: Boolean): Seq[CellPair] = {
    values.foldLeft(Seq.empty[CellPair]) { (acc, e) =>
      val rowId = e._1
      val ownCv = e._2
      otherVersion.values.get(rowId) match {
        case Some(otherCv) =>
          if (ownCv.date > otherCv.date || (prioritizeOwn && (ownCv.date == otherCv.date))) e +: acc else acc
        case None => e +: acc
      }
    }
  }

  def newerOwnCells (otherCellDates: Seq[RowDatePair], addMissing: Boolean, prioritizeOwn: Boolean=false): Seq[CellPair] = {
    val newerOwnCells = mutable.Buffer.empty[CellPair]
    val seenRows = mutable.Set.empty[String]

    otherCellDates.foreach { e =>
      val rowId = e._1
      val rowDate = e._2

      seenRows += rowId

      values.get(rowId) match {
        case Some(cv: CellValue[_]) =>
          if ((cv.date > rowDate) || (prioritizeOwn && (cv.date == rowDate))) {
            newerOwnCells += (rowId -> cv)
          }
        case _ =>  // we don't have this CV
      }
    }

    if (addMissing) {
      values.foreach { e =>
        val rowId = e._1
        val cv = e._2
        if (!seenRows.contains(rowId)) {
          newerOwnCells += (rowId -> cv)
        }
      }
    }

    newerOwnCells.toSeq
  }

  def outdatedOwnCells (otherCellDates: Seq[RowDatePair], prioritizeOther: Boolean): Seq[RowDatePair] = {
    val outdatedOwnCells = mutable.Buffer.empty[RowDatePair]
    otherCellDates.foreach { e =>
      val rowId = e._1
      val rowDate = e._2

      values.get(rowId) match {
        case Some(cv: CellValue[_]) =>
          if ((cv.date < rowDate) /* || (prioritizeOther && (cv.date == rowDate)) */) {
            // TODO - adding same time rows is not really helpful since the clocks are probably not synced to the same msec
            outdatedOwnCells += (rowId -> cv.date)
          }
        case _ =>
          outdatedOwnCells += (rowId -> DateTime.Date0)
      }
    }

    outdatedOwnCells.toSeq
  }

  def differingCellValues (cdc: ColumnDataChange): Seq[(String,CellValue[_],CellValue[_])] = {
    cdc.changedValues.foldRight(Seq.empty[(String,CellValue[_],CellValue[_])]) { (e, delta) =>
      val rid = e._1
      val rcv = e._2
      values.get(rid) match {
        case Some(cv) =>
          if (!cv.equals(rcv)) {
            val e = new Tuple3[String,CellValue[_],CellValue[_]](rid,cv,rcv) // compiler otherwise complains about implicit inferred type use
            e +: delta
          } else delta
        case None =>
          val e = new Tuple3[String,CellValue[_],CellValue[_]](rid,rcv.undefinedCellValue,rcv)
          e +: delta
      }
    }
  }

  def orderedTimeStamps (rowList: RowList): Seq[(String,DateTime)] = {
    rowList.rows.foldRight(Seq.empty[(String,DateTime)]) { (e,acc) =>
      values.get(e._1) match {
        case Some(cv) => (e._1 -> cv.date) +: acc
        case None => acc
      }
    }
  }
}

/**
  * parser for provider data
  *
  * TODO - this should have a map of FieldCatalogs as ctor args so that we can check against the right fields based on
  * what is specified in the PD
  */
class ColumnDataParser (rowList: RowList) extends UTF8JsonPullParser {

  def fillInDefaultDates(map: Map[String,CellValue[_]], date: DateTime): Map[String,CellValue[_]] = {
    map.map( e=> if (e._2.date.isUndefined) (e._1 -> e._2.copyWithDate(date)) else e )
  }

  def readRows(defaultDate: DateTime): Map[String,CellValue[_]] = {
    var latestChange = DateTime.Date0
    var map = Map.empty[String,CellValue[_]]

    foreachInCurrentObject {
      val rowId = member.intern
      rowList.get(rowId) match {
        case Some(row) =>
          val cv = row.parseValueFrom(this)(defaultDate)
          if (cv.date > latestChange) latestChange = cv.date
          map = map + (rowId -> cv)
        case None =>
          warning(s"skipping unknown row $rowId") // we don't treat this as an error since the RowList might have changed
      }
    }

    map
  }

  def readColumnData(): ColumnData = {
    var id: String = null
    var date: DateTime = DateTime.UndefinedDateTime
    var checkDates: Boolean = false
    var rows: Map[String,CellValue[_]] = Map.empty

    foreachMemberInCurrentObject {
      case ID => id = quotedValue.toString
      case DATE => date = dateTimeValue
      case ROWS =>
        if (date.isUndefined) checkDates = true
        rows = readCurrentObject( readRows(date) )
    }
    if (id == null) throw exception("missing id in columnData")
    if (!date.isDefined) throw exception("missing date in columnData")
    if (checkDates) rows = fillInDefaultDates(rows,date)

    ColumnData(id, date, rows)
  }

  def parse (buf: Array[Byte]): Option[ColumnData] = {
    initialize(buf)

    try {
      readNextObject {
        Some( readNextObjectMember(COLUMN_DATA) { readColumnData() } )
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed columnList: ${x.getMessage}")
        None
    }
  }
}

object ColumnDataChange extends JsonConstants {
  // lexical constants for serialization/deserialization of ProviderDataChange objects
  val COLUMN_DATA_CHANGE : ConstAsciiSlice = asc("columnDataChange")
  val COLUMN_ID : ConstAsciiSlice = asc("columnId") // the provider id
  val CHANGE_NODE_ID : ConstAsciiSlice = asc("changeNodeId")
  val CHANGED_VALUES : ConstAsciiSlice = asc("changedValues")
}

/**
  * event to change ColumnData
  * note this is per-column, i.e. we only update the data model one column at a time
  *
  * 'date' is the change date for the associated fieldValues source, i.e. the source revision this change set is based on
  */
case class ColumnDataChange(columnId: String,
                            changeNodeId: String, // from which node we get the PDC
                            date: DateTime,       // [R] this has to be >= latest date in changedValues
                            changedValues: Seq[CellPair]
                           ) extends JsonMessageObject {
  import ColumnDataChange._

  /** order of fieldValues should not matter */
  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeObjectMember(COLUMN_DATA_CHANGE) { _
      .writeStringMember(COLUMN_ID, columnId.toString)
      .writeStringMember(CHANGE_NODE_ID, changeNodeId.toString)
      .writeDateTimeMember(DATE, date)
      .writeObjectMember(CHANGED_VALUES) { w =>
        changedValues.foreach { fv =>
          w.writeMemberName(fv._1.toString)
          val wasFormatted = w.format(false) // print this dense no matter what
          fv._2.serializeTo(w)  // this writes both value and date
          w.format(wasFormatted)
        }
      }
    }
  }

  // TODO - this is not very efficient for large change sets
  def filter (f: String=>Boolean): ColumnDataChange = copy(changedValues = changedValues.filter(cell=> f(cell._1)))

  def size: Int = changedValues.size
  def nonEmpty: Boolean = changedValues.nonEmpty
}

/**
  * a parser for ProviderDataChange messages
  * note this is a trait so that we can compose parsers for specific message sets
  *
  * note also that CDCs can be sent by external devices or processes so we should not rely on a specific
  * ordering of members
  */
trait ColumnDataChangeParser extends JsonPullParser {
  import ColumnDataChange._

  def rowList: RowList // to be provided by concrete type

  def fillInDates (cvs: Seq[CellPair], date: DateTime): Seq[CellPair] = {
    cvs.map( e=> if (e._2.date.isDefined) e else (e._1 -> e._2.copyWithDate(date)))
  }

  def readChangedValues(date: DateTime): Seq[CellPair] = {
    val cvs = mutable.Buffer.empty[CellPair]

    foreachInCurrentObject {
      val rowId = member.intern
      rowList.get(rowId) match {
        case Some(row) => cvs += (rowId -> row.parseValueFrom(this)(date))
        case None =>
          warning(s"unknown row '$rowId' in columnDataChange message ignored")
          if (isLevelStart) {
            readNext()
            skipToEndOfCurrentLevel()
          } else {
            readNext()
          }
      }
    }

    cvs.toSeq
  }

  // the 'columnDataChange' member name has already been parsed
  def parseColumnDataChange(): Option[ColumnDataChange] = {
    var columnId: String = null
    var changeNodeId: String = null
    var date: DateTime = DateTime.UndefinedDateTime
    var cvs = Seq.empty[CellPair]
    var checkDates = false

    tryParse( x=> warning(s"malformed columnDataChange: ${x.getMessage}") ) {
      foreachMemberInCurrentObject {
        case COLUMN_ID => columnId = quotedValue.intern
        case CHANGE_NODE_ID => changeNodeId = quotedValue.intern
        case DATE => date = dateTimeValue
        case CHANGED_VALUES =>
          checkDates = date.isDefined
          cvs = readCurrentObject( readChangedValues(date))
      }

      if (columnId == null) throw exception(s"missing '$COLUMN_ID' in columnDataChange")
      if (changeNodeId == null) throw exception(s"missing '$CHANGE_NODE_ID' in columnDataChange")
      if (date.isUndefined) throw exception(s"missing '$DATE' in columnDataChange")
      if (checkDates) cvs = fillInDates(cvs,date)

      ColumnDataChange(columnId,changeNodeId,date,cvs)
    }
  }

  def parse (): Option[ColumnDataChange] = {
    try {
      readNextObject {
        readNextObjectMember(COLUMN_DATA_CHANGE){ parseColumnDataChange() }
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed columnDataChange: ${x.getMessage}")
        //x.printStackTrace()
        None
    }
  }
}

/**
  * column-wise cell constraint violations
  */
case class ColumnDataConstraintViolations(columnId: String, date: DateTime, cells: Seq[(String,BoolCellFormula)])