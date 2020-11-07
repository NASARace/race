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

import gov.nasa.race.common.JsonSerializable
import gov.nasa.race.core.Loggable
import gov.nasa.race.withSomeOrElse

import scala.collection.immutable.Iterable
import scala.collection.mutable

/**
  * object that responds to remote NodeState messages with
  *   - one own NodeState for outdated local ColumnData, and
  *   - ColumnDataChanges for each ColumnData that is outdated on remote
  *
  * this class essentially implements the NodeState sync protocol
  */
class NodeStateResponder (node: Node, columnData: Map[Path,ColumnData],
                          logger: Loggable,
                          sendColumn: Column => Boolean,
                          receiveColumn: Column => Boolean,
                          sendRow: (Column,AnyRow) => Boolean,
                          receiveRow: (Column,AnyRow) => Boolean
                         ) {

  /**
    * the exported function for which we exist
    */
  def getNodeStateReplies (ns: NodeState): Seq[JsonSerializable] = {
    val remoteNodeId = ns.nodeId

    if (node.isKnownColumn(remoteNodeId)) {
      val extOutdated = mutable.Buffer.empty[ColumnDate]
      val locOutdated = mutable.Buffer.empty[(Path,Seq[RowDate])]
      val updates = mutable.Buffer.empty[(Path,Seq[Cell])]

      processExternalColumnDates( node, remoteNodeId, ns.readOnlyColumns, extOutdated, updates)
      processLocalColumnDates( node, remoteNodeId, ns.readWriteColumns, locOutdated, updates)

      // TODO - what about CDs we export to remote that are not in the NS? (indicates outdated CLs)

      val replies = mutable.ArrayBuffer.empty[JsonSerializable]

      updates.foreach { e=>
        val (colId,cells) = e
        replies += ColumnDataChange( colId, node.id, columnData(colId).date, cells)
      }

      // we send the own NodeState last as the handshake terminator (even if there are no outdated own CDs)
      replies += new NodeState(node, extOutdated.toSeq, locOutdated.toSeq)

      replies.toSeq

    } else {
      logger.warning(s"rejecting NodeState from unknown node $remoteNodeId")
      Seq.empty[JsonSerializable]
    }
  }

  protected def processExternalColumnDates (node: Node, remoteNodeId: Path, externalColumnDates: Seq[ColumnDate],
                                            outdatedOwn: mutable.Buffer[ColumnDate], newerOwn: mutable.Buffer[(Path,Seq[Cell])]): Unit = {
    externalColumnDates.foreach { e=>
      val (colId, colDate) = e

      columnData.get(colId) match {
        case Some(cd) =>
          val col = node.columnList(colId)  // if we have a CD it also means we have a column for it

          if (colDate < cd.date) { // our data is newer
            if (sendColumn(col)) {
              // this is not our data, so we don't include cells that have the same time stamp
              val cells = cd.changesSince(colDate).filter( cell=> sendRow(col, node.rowList(cell._1)))
              if (cells.nonEmpty) newerOwn += (colId -> cells)
            }

          } else if (colDate > cd.date) { // remote data is newer
            if (receiveColumn(col)) {
              outdatedOwn += (colId -> cd.date)
            }
          }
          // since we only receive and don't modify this CD equal timestamps are treated as up-to-date

        case None => // we don't know this remote column
          logger.warning(s"unknown column ${e._1}")
      }
    }
  }

  protected def processLocalColumnDates (node: Node, remoteNodeId: Path, localColumnDates: Seq[(Path,Seq[RowDate])],
                                         outdatedOwn: mutable.Buffer[(Path,Seq[RowDate])], newerOwn: mutable.Buffer[(Path,Seq[Cell])]): Unit = {
    localColumnDates.foreach { e=>
      val (colId,rowDates) = e

      columnData.get(colId) match {
        case Some(cd) =>
          val col = node.columnList(colId) // if we have a CD it also means we have a column for it
          val prioritizeOwn = col.node == node.id
          val addMissing = true

          if (sendColumn(col)) {
            val noc = cd.newerOwnCells(rowDates, addMissing, prioritizeOwn).filter( cell=> sendRow(col,node.rowList(cell._1)))
            if (noc.nonEmpty) newerOwn += (colId -> noc)
          }

          if (receiveColumn(col)) {
            val ooc = cd.outdatedOwnCells(rowDates, !prioritizeOwn).filter( rd=> receiveRow(col,node.rowList(rd._1)))
            if (ooc.nonEmpty) outdatedOwn += (colId -> ooc)
          }

        case None => // we don't know this remote column
          logger.warning(s"unknown column ${e._1}")
      }
    }
  }

}
