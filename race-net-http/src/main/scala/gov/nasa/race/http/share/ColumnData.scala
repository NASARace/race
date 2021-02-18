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
package gov.nasa.race.http.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{ConstAsciiSlice, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.{immutable, mutable}


object ColumnData {
  //--- lexical constants
  val _id_ = asc("id") // the provider id
  val _rows_ = asc("rows")
  val _columnListId_ = asc("columnlist")
  val _rowListId_ = asc("rowlist")
  val _value_ = asc("value")
  val _date_ = asc("date")

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
import gov.nasa.race.http.share.ColumnData._

/**
  * instance type of a provider that holds the cell values
  *
  * 'date' always has to satisfy the ">= latest cellValue update" property
  *
  * CDs can be used with different RowLists - the dominant requirement is to〖 avoid redundant data storage (both
  * online and serialized) 〗
  */
case class ColumnData (id: String, date: DateTime, values: Map[String,CellValue[_]]) extends JsonSerializable {

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

  private def _serializeTo (w: JsonWriter)(valueSerializer: JsonWriter=>Unit): Unit = {
    w.clear().writeObject( _
      .writeMemberObject("columnData") { _
        .writeStringMember(_id_, id.toString)
        .writeDateTimeMember(_date_, date)
        .writeMemberObject(_rows_) { w => valueSerializer(w) }
      }
    )
  }

  def serializeTo (w: JsonWriter): Unit = {
    _serializeTo(w){ w=>
      values.foreach { cell =>
        w.writeMemberName(cell._1.toString)
        val wasFormatted = w.format(false) // print this dense no matter what
        cell._2.serializeTo(w)
        w.format(wasFormatted)
      }
    }
  }

  def serializeOrderedTo (w: JsonWriter, rowList: RowList): Unit = {
    _serializeTo(w) { w =>
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
    * note - result not ordered
    */
  def changesSince (refDate: DateTime, prioritizeOwn: Boolean): Seq[CellPair] = {
    values.foldLeft(Seq.empty[CellPair]) { (acc, e) =>
      val ownCv = e._2
      if (ownCv.date > refDate || (prioritizeOwn && (ownCv.date == refDate))) e +: acc else acc
    }
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
          if ((cv.date < rowDate) || (prioritizeOther && (cv.date == rowDate))) {
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
class ColumnDataParser(rowList: RowList) extends UTF8JsonPullParser {

  def parse (buf: Array[Byte]): Option[ColumnData] = {
    var latestChange = DateTime.Date0 // to hold the latest fieldValue change date (if all specified)

    // we parse both explicit
    //     "<rowId>": { "value": <num>|<string>|<array> [, "date": <epoch-ms>] }
    // and implicit
    //     "<rowId>": <num>|<sting>|<array>
    // if no date is specified we use the parseDate to instantiate FieldValues
    def readCell(defaultDate: DateTime): Option[(String,CellValue[_])] = {
      val rowId = readMemberName().intern
      rowList.get(rowId) match {
        case Some(row) =>
          val cv = row.parseValueFrom(this)(defaultDate)
          if (cv.date > latestChange) latestChange = cv.date
          Some((rowId,cv))
        case None =>
          warning(s"skipping unknown row $rowId")
          None
      }
    }

    initialize(buf)

    try {
      ensureNextIsObjectStart()
      val id = readQuotedMember(_id_).intern
      val date = readDateTimeMember(_date_)

      val cells = readSomeNextObjectMemberInto[String,CellValue[_],mutable.Map[String,CellValue[_]]](_rows_,mutable.Map.empty){
        readCell(date)
      }.toMap

      Some(ColumnData(id,latestChange,cells)) // use latestChange as the date to make sure it is consistent

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerData: ${x.getMessage}")
        None
    }
  }
}

object ColumnDataChange {
  // lexical constants for serialization/deserialization of ProviderDataChange objects
  val _columnDataChange_ : ConstAsciiSlice = asc("columnDataChange")
  val _columnId_ : ConstAsciiSlice = asc("columnId") // the provider id
  val _date_ : ConstAsciiSlice = asc("date")
  val _changeNodeId_ : ConstAsciiSlice = asc("changeNodeId")
  val _changedValues_ : ConstAsciiSlice = asc("changedValues")
  val _value_ : ConstAsciiSlice = asc("value")
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
                           ) extends JsonSerializable {
  import ColumnDataChange._

  /** order of fieldValues should not matter */
  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject { _
      .writeMemberObject(_columnDataChange_) { _
        .writeStringMember(_columnId_, columnId.toString)
        .writeStringMember(_changeNodeId_, changeNodeId.toString)
        .writeDateTimeMember(_date_, date)
        .writeMemberObject(_changedValues_) { w =>
          changedValues.foreach { fv =>
            w.writeMemberName(fv._1.toString)
            val wasFormatted = w.format(false) // print this dense no matter what
            fv._2.serializeTo(w)  // this writes both value and date
            w.format(wasFormatted)
          }
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
  */
trait ColumnDataChangeParser extends JsonPullParser {
  import ColumnDataChange._

  def rowList: RowList // to be provided by concrete type

  def parseColumnDataChange: Option[ColumnDataChange] = {
    readNextObjectMember(_columnDataChange_) {
      parseColumnDataChangeBody
    }
  }

  def parseColumnDataChangeBody: Option[ColumnDataChange] = {
    def readCell (defaultDate: DateTime): Option[(String,CellValue[_])] = {
      val rowId = readObjectMemberName().intern

      rowList.get(rowId) match {
        case Some(row) =>
          Some(rowId -> row.parseValueFrom(this)(defaultDate))
        case None =>
          warning(s"unknown field '$rowId' in providerDataChange message ignored")
          None
      }
    }

    tryParse( x=> warning(s"malformed providerDataChange: ${x.getMessage}") ) {
      val columnId = readQuotedMember(_columnId_).intern
      val changeNodeId = readQuotedMember(_changeNodeId_).intern
      val date = readDateTimeMember(_date_)

      val cells = readSomeNextObjectMemberInto[String,CellValue[_],mutable.Buffer[(String,CellValue[_])]](_changedValues_,mutable.Buffer.empty){
        readCell(date)
      }.toSeq

      ColumnDataChange(columnId,changeNodeId,date,cells)
    }
  }
}

/**
  * column-wise cell constraint violations
  */
case class ColumnDataConstraintViolations(columnId: String, date: DateTime, cells: Seq[(String,BoolCellFormula)])