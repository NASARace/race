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
import gov.nasa.race.common.JsonPullParser.{ObjectStart, UnQuotedValue}
import gov.nasa.race.common.{ConstAsciiSlice, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, MutAsciiSlice, UTF8JsonPullParser, UnixPath}
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
}
import ColumnData._

/**
  * instance type of a provider that holds field values
  *
  * 'date' is the latest fieldValues change date
  *
  * note this does not have an explicit rev since we keep track of value change dates inside of FieldValues
  * (since changes can be initiated up- or down-stream rev numbers could collide without a sync protocol that
  * would introduce a single point of failure)
  */
case class ColumnData (id: Path,
                       date: DateTime,
                       columnListId: Path,
                       rowListId: Path,
                       rows: immutable.Map[Path,CellValue]
                      ) extends JsonSerializable {

  def get (pRow: Path): Option[CellValue] = rows.get(pRow)

  private def _serializeTo (w: JsonWriter)(valueSerializer: JsonWriter=>Unit): Unit = {
    w.clear.writeObject( _
      .writeMemberObject("columnData") { _
        .writeStringMember(_id_, id.toString)
        .writeDateTimeMember(_date_, date)
        .writeStringMember(_columnListId_, columnListId.toString)
        .writeStringMember(_rowListId_, rowListId.toString)
        .writeMemberObject(_rows_) { w => valueSerializer(w) }
      }
    )
  }

  def serializeTo (w: JsonWriter): Unit = {
    _serializeTo(w){ w=>
      rows.foreach { cell =>
        w.writeMemberName(cell._1.toString)
        val wasFormatted = w.format(false) // print this dense no matter what
        cell._2.serializeTo(w)
        w.format(wasFormatted)
      }
    }
  }

  def serializeOrderedTo (w: JsonWriter, rowList: RowList): Unit = {
    _serializeTo(w) { w =>
      rowList.foreach { e=>
        val rid = e._1
        rows.get(rid) match {
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

  //--- change management operations

  def sameIdsAs (other: ColumnData): Boolean = {
    (id == other.id)
  }

  def changesSince (refDate: DateTime): Seq[(Path,CellValue)] = {
    rows.foldLeft(Seq.empty[(Path,CellValue)]) { (acc, e) =>
      val cv = e._2
      if (cv.date > refDate) e +: acc else acc
    }
  }

  def differingCellValues (cdc: ColumnDataChange): Seq[(Path,CellValue,CellValue)] = {
    cdc.cells.foldRight(Seq.empty[(Path,CellValue,CellValue)]) { (e,delta) =>
      val (rid,rcv) = e
      rows.get(rid) match {
        case Some(cv) => if (!cv.equals(rcv)) (rid,cv,rcv) +: delta else delta
        case None => (rid,rcv.undefinedCellValue,rcv) +: delta
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
    //     "<rowId>": { "value": <num>|<sting>|<array> [, "date": <epoch-ms>] }
    // and implicit
    //     "<rowId>": <num>|<sting>|<array>
    // if no date is specified we use the parseDate to instantiate FieldValues
    def readCell(rowListId: Path, defaultDate: DateTime): Option[(Path,CellValue)] = {
      val rowId = rowListId.resolve( UnixPath.intern(readMemberName()))
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
      val sid = UnixPath.intern(readQuotedMember(_id_))
      val date = readDateTimeMember(_date_)

      val columnListId = UnixPath.intern(readQuotedMember(_columnListId_))
      val rowListId = UnixPath.intern(readQuotedMember(_rowListId_))

      val id = columnListId.resolve(sid)

      val cells = readSomeNextObjectMemberInto[Path,CellValue,mutable.Map[Path,CellValue]](_rows_,mutable.Map.empty){
        readCell(rowListId,date)
      }.toMap

      Some(ColumnData(id,latestChange,columnListId,rowListId,cells)) // use latestChange as the date to make sure it is consistent

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
  val _cells_ : ConstAsciiSlice = asc("cells")
  val _value_ : ConstAsciiSlice = asc("value")
}

/**
  * ProviderData change set
  *
  * 'date' is the change date for the associated fieldValues source, i.e. the source revision this change set is based on
  */
case class ColumnDataChange(columnId: Path,
                            changeNodeId: Path, // from which node we get the PDC
                            date: DateTime,
                            cells: Seq[(Path,CellValue)]
                              ) extends JsonSerializable {
  import ColumnDataChange._

  /** order of fieldValues should not matter */
  def serializeTo (w: JsonWriter): Unit = {
    w.clear.writeObject { _
      .writeMemberObject(_columnDataChange_) { _
        .writeStringMember(_columnId_, columnId.toString)
        .writeStringMember(_changeNodeId_, changeNodeId.toString)
        .writeDateTimeMember(_date_, date)
        .writeMemberObject(_cells_) { w =>
          cells.foreach { fv =>
            w.writeMemberName(fv._1.toString)
            val wasFormatted = w.format(false) // print this dense no matter what
            fv._2.serializeTo(w)  // this writes both value and date
            w.format(wasFormatted)
          }
        }
      }
    }
  }
}

/**
  * a parser for ProviderDataChange messages
  * note this is a trait so that we can compose parsers for specific message sets
  */
trait ColumnDataChangeParser extends JsonPullParser {
  import ColumnDataChange._

  val node: Node // to be provided by concrete type

  def parseColumnDataChange: Option[ColumnDataChange] = {
    readNextObjectMember(_columnDataChange_) {
      parseColumnDataChangeBody
    }
  }

  def parseColumnDataChangeBody: Option[ColumnDataChange] = {
    val valueSlice = MutAsciiSlice.empty

    def readCell(): Option[(Path,CellValue)] = {
      val rowId = UnixPath.intern(readObjectMemberName())
      valueSlice.setFrom(readUnQuotedMember(_value_)) // TODO this should also handle arrays
      implicit val date = readDateTimeMember(_date_)
      skipPastAggregate()

      node.rowList.get(rowId) match {
        case Some(row) =>
          Some(rowId -> row.parseValueFrom(this))
        case None =>
          warning(s"unknown field '$rowId' in providerDataChange message ignored")
          None
      }
    }

    try {
      val columnId = UnixPath.intern(readQuotedMember(_columnId_))
      val changeNodeId = UnixPath.intern(readQuotedMember(_changeNodeId_))
      val date = readDateTimeMember(_date_)

      val cells = readSomeNextObjectMemberInto[Path,CellValue,mutable.Buffer[(Path,CellValue)]](_cells_,mutable.Buffer.empty){
        readCell()
      }.toSeq

      Some(ColumnDataChange(columnId,changeNodeId,date,cells))

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerDataChange: ${x.getMessage}")
        None
    }
  }
}

