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
import gov.nasa.race.common.{ConstAsciiSlice, JsonPullParser, JsonSerializable, JsonWriter, UnixPath}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable

/**
  * the mostly static data for a site, for internal distribution
  * this is the minimum data each client / server needs
  */
case class Node ( id: Path,
                  rowList: RowList,
                  columnList: ColumnList,
                  upstreamId: Option[Path]
                 ) {

  def isKnownColumn(id: Path): Boolean = columnList.columns.contains(id) || (upstreamId.isDefined && upstreamId.get == id)
  def isKnownRow(id: Path): Boolean = rowList.rows.contains(id)

  def isColumnListUpToDate(ss: NodeState): Boolean = {
    columnList.id == ss.columnListId && columnList.date == ss.columnListDate
  }

  def isRowListUpToDate(ss: NodeState): Boolean = {
    rowList.id == ss.rowListId && rowList.date == ss.rowListDate
  }

  def isLocal (p: Path): Boolean = id == p
  def isUpstream (p: Path): Boolean = upstreamId.isDefined && upstreamId.get == p

  def sendChangeUp (columnId: Path): Boolean = {
    true
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
  val _externalColumnDates_ : ConstAsciiSlice = asc("externalColumnDates")
  val _localColumnDates_ : ConstAsciiSlice = asc("localColumnDates")
}

/**
  * site specific snapshot of local catalog and provider data dates (last modifications)
  *
  * we distinguish between external and local columns. The former we don't write on this node and hence only
  * get them through our upstream (hence a single update date will suffice). The locals could be modified
  * concurrently between us and upstream, hence we need the cell dates
  */
case class NodeState(nodeId: Path,
                     rowListId: Path, rowListDate: DateTime,
                     columnListId: Path, columnListDate: DateTime,
                     externalColumnDates: Seq[ColumnDate], // for external columns we only need the CD date
                     localColumnDates: Seq[(Path,Seq[RowDate])] // for local columns we need the CD row dates
                     ) extends JsonSerializable {
  import NodeState._

  def this (nodeId: Path, rowList: RowList, columnList: ColumnList, columnData: collection.Map[Path,ColumnData]) = this(
    nodeId,
    rowList.id,rowList.date,
    columnList.id,columnList.date,
    columnList.filteredEntries(columnData)(_.node != nodeId).map( e=> (e._1,e._2.date) ),
    columnList.filteredEntries(columnData)(_.node == nodeId).map( e=>(e._1, e._2.orderedTimeStamps(rowList)) )
  )

  def this (node: Node, columnData: collection.Map[Path,ColumnData]) = this(
    node.id,
    node.rowList,
    node.columnList,
    columnData
  )

  def this (node: Node, externalColumnDates: Seq[ColumnDate], localColumnDates: Seq[(Path,Seq[RowDate])]) = this(
    node.id,
    node.rowList.id,
    node.rowList.date,
    node.columnList.id,
    node.columnList.date,
    externalColumnDates,
    localColumnDates
  )


  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject {_
      .writeMemberObject(_nodeState_) {_
        .writeStringMember(_nodeId_, nodeId.toString)
        .writeStringMember(_rowListId_, rowListId.toString)
        .writeDateTimeMember(_rowListDate_, rowListDate)
        .writeStringMember(_columnListId_, columnListId.toString)
        .writeDateTimeMember(_columnListDate_, columnListDate)

        // "externalColumns": { "<colId>": <date>, ... }
        .writeMemberObject(_externalColumnDates_) { w =>
          externalColumnDates.foreach { e =>
            w.writeDateTimeMember(e._1.toString, e._2)
          }
        }

        // "localColumns": { "<colId>": { "<rowId>": <date>, ... } }
        .writeMemberObject(_localColumnDates_) { w =>
          localColumnDates.foreach { e=>
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
    val siteId = UnixPath(readQuotedMember(_nodeId_))
    val rowListId = UnixPath(readQuotedMember(_rowListId_))
    val rowListDate = readDateTimeMember(_rowListDate_)
    val columnListId = UnixPath(readQuotedMember(_columnListId_))
    val columnListDate = readDateTimeMember(_columnListDate_)

    // "externalColumns": { "<colId>": <date>, ... }
    val externalColumnDates = readSomeNextObjectMemberInto[Path,DateTime, mutable.Buffer[(Path, DateTime)]](_externalColumnDates_, mutable.Buffer.empty) {
      val columnDataDate = readDateTime()
      val columnId = UnixPath.intern(member)
      Some( (columnId -> columnDataDate) )
    }.toSeq

    // "localColumns": { "<colId>": { "<rowId>": <date>, ... } }
    val localColumnDates = readSomeNextObjectMemberInto[Path,Seq[PathDate], mutable.Buffer[(Path,Seq[RowDate])]](_localColumnDates_, mutable.Buffer.empty) {
      val columnId = UnixPath.intern(readMemberName())
      val rowDates = readSomeCurrentObjectInto[Path,DateTime, mutable.Buffer[PathDate]](mutable.Buffer.empty) {
        val rowDataDate = readDateTime()
        val rowId = UnixPath.intern(member)
        Some(rowId -> rowDataDate)
      }.toSeq
      Some(columnId -> rowDates)
    }.toSeq

    Some(NodeState(siteId, rowListId, rowListDate, columnListId, columnListDate, externalColumnDates, localColumnDates))
  }

}
