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
import gov.nasa.race.common.{ConstAsciiSlice, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.StringUtils

import scala.collection.mutable

/**
  * the data model for each node, consisting of invariant (start-time) data such as the column/rowList(s) and the
  * variable columnData for each of the columnList entries
  *
  * note that Node objects are invariant so that they can be passed around between actors. Mutators for columnData
  * therefore have to return new Node objects
  *
  * we keep all the data in one structure so that actors do not have to handle transient (partial) initialization states
  */
case class Node (id: String,
                 upstreamId: Option[String],
                 columnList: ColumnList,
                 rowList: RowList, // TODO - is this the union of column rowLists ?
                 columnDatas: Map[String,ColumnData]
                ) {

  def isKnownColumn(id: String): Boolean = columnList.columns.contains(id) || (upstreamId.isDefined && upstreamId.get == id)
  def isKnownRow(id: String): Boolean = rowList.rows.contains(id)

  def isColumnListUpToDate(ss: NodeState): Boolean = {
    columnList.id == ss.columnListId && columnList.date == ss.columnListDate
  }

  def isRowListUpToDate(ss: NodeState): Boolean = {
    rowList.id == ss.rowListId && rowList.date == ss.rowListDate
  }

  def isLocal (p: String): Boolean = id == p
  def isUpstream (p: String): Boolean = upstreamId.isDefined && upstreamId.get == p

  def sendChangeUp (columnId: String): Boolean = {
    true
  }

  def cellValue (colId: String, rowId: String): CellValue[_] = columnDatas(colId)(rowList(rowId))

  def apply (colId: String): ColumnData = columnDatas(colId)
  def get (colId: String): Option[ColumnData] = columnDatas.get(colId)
  def get (colId: String, rowId: String): Option[CellValue[_]] = get(colId).flatMap( _.get(rowId))

  def getEvalContext (date: DateTime = DateTime.UndefinedDateTime): EvalContext = new BasicEvalContext(id,rowList,date,columnDatas)

  def update (cd: ColumnData): Node = copy(columnDatas= columnDatas + (cd.id -> cd))

  //--- debugging
  def printColumnData(): Unit = {
    print("row                  |")
    columnList.foreach { col=> print(f"${PathIdentifier.name(col.id)}%15.15s |") }
    println()
    print("---------------------+") // row name
    var i = columnList.size
    while (i > 0) { print("----------------+"); i-= 1 }
    println()

    rowList.foreach { row =>
      print(f"${StringUtils.maxSuffix(row.id,20)}%-20.20s |")
      columnList.foreach { col =>
        columnDatas.get(col.id) match {
          case Some(cd) =>
            cd.get(row.id) match {
              case Some(cv) =>  print(f"${cv.valueToString}%15.15s |")
              case None => print("              - |")
            }
          case None => print("              - |")
        }
      }
      println()
    }
  }
}


object NodeState {
  //--- lexical constants
  val _nodeState_ : ConstAsciiSlice = asc("nodeState")
  val _nodeId_ : ConstAsciiSlice = asc("id")
  val _rowListId_ : ConstAsciiSlice = asc("rowListId")
  val _rowListDate_ : ConstAsciiSlice = asc("rowListDate")
  val _columnListId_ : ConstAsciiSlice = asc("columnListId")
  val _columnListDate_ : ConstAsciiSlice = asc("columnListDate")
  val _readOnlyColumns_ : ConstAsciiSlice = asc("readOnlyColumns")
  val _readWriteColumns_ : ConstAsciiSlice = asc("readWriteColumns")

  def apply (node: Node, externalColumnDates: Seq[ColumnDatePair], localColumnDates: Seq[(String,Seq[RowDatePair])]) = {
    new NodeState(node.id,
      node.rowList.id,
      node.rowList.date,
      node.columnList.id,
      node.columnList.date,
      externalColumnDates,
      localColumnDates)
  }
}

/**
  * site specific snapshot of local catalog and provider data dates (last modifications)
  *
  * we distinguish between external and locally modified columns. The former we don't write on this node and hence only
  * get them through our upstream (a single update date will suffice to determine what the external needs to send to
  * update).
  * The ColumnData rows we produce ourselves could be modified concurrently between us and upstream, hence we need the
  * cell dates, i.e. check row-by-row
  *
  * this class and (our own) Node is basically the data model for node synchronization, the dynamic part of it being
  * factored out into NodeStateResponder
  */
case class NodeState(nodeId: String,
                     rowListId: String, rowListDate: DateTime,
                     columnListId: String, columnListDate: DateTime,
                     readOnlyColumns: Seq[ColumnDatePair], // for external columns we only need the CD date
                     readWriteColumns: Seq[(String,Seq[RowDatePair])] // for locally written  columns we need the CD row dates
                     ) extends JsonSerializable {
  import NodeState._

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject {_
      .writeMemberObject(_nodeState_) {_
        .writeStringMember(_nodeId_, nodeId.toString)
        .writeStringMember(_rowListId_, rowListId.toString)
        .writeDateTimeMember(_rowListDate_, rowListDate)
        .writeStringMember(_columnListId_, columnListId.toString)
        .writeDateTimeMember(_columnListDate_, columnListDate)

        // "readOnlyColumns": { "<colId>": <date>, ... }
        .writeMemberObject(_readOnlyColumns_) { w =>
          readOnlyColumns.foreach { e =>
            w.writeDateTimeMember(e._1.toString, e._2)
          }
        }

        // "readWriteColumns": { "<colId>": { "<rowId>": <date>, ... } }
        .writeMemberObject(_readWriteColumns_) { w =>
          readWriteColumns.foreach { e=>
            val (colId,rowDates) = e
            w.writeMemberObject(colId.toString) { w=>
              rowDates.foreach { cvd =>
                w.writeDateTimeMember(cvd._1.toString, cvd._2)
              }
            }
          }
        }
      }
    }
  }
}

/**
  * parser mixin for NodeState
  */
trait NodeStateParser extends JsonPullParser {
  import NodeState._

  def parseNodeState: Option[NodeState] = {
    readNextObjectMember(_nodeState_) {
      parseNodeStateBody
    }
  }

  def parseNodeStateBody: Option[NodeState] = {
    val siteId = readQuotedMember(_nodeId_).toString
    val rowListId = readQuotedMember(_rowListId_).toString
    val rowListDate = readDateTimeMember(_rowListDate_)
    val columnListId = readQuotedMember(_columnListId_).toString
    val columnListDate = readDateTimeMember(_columnListDate_)

    // "externalColumns": { "<colId>": <date>, ... }
    val externalColumnDates = readSomeNextObjectMemberInto[String,DateTime, mutable.Buffer[ColumnDatePair]](_readOnlyColumns_, mutable.Buffer.empty) {
      val columnDataDate = readDateTime()
      val columnId = member.toString
      Some( (columnId -> columnDataDate) )
    }.toSeq

    // "localColumns": { "<colId>": { "<rowId>": <date>, ... } }
    val localColumnDates = readSomeNextObjectMemberInto[String,Seq[ColumnDatePair], mutable.Buffer[(String,Seq[RowDatePair])]](_readWriteColumns_, mutable.Buffer.empty) {
      val columnId = readMemberName().toString
      val rowDates = readSomeCurrentObjectInto[String,DateTime, mutable.Buffer[RowDatePair]](mutable.Buffer.empty) {
        val rowDataDate = readDateTime()
        val rowId = member.toString
        Some(rowId -> rowDataDate)
      }.toSeq
      Some(columnId -> rowDates)
    }.toSeq

    Some(NodeState(siteId, rowListId, rowListDate, columnListId, columnListDate, externalColumnDates, localColumnDates))
  }

}
