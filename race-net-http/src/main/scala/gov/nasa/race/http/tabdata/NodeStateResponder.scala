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

import gov.nasa.race.common.JsonSerializable
import gov.nasa.race.core.Loggable

import scala.collection.mutable

/**
  * object that responds to remote NodeState messages with
  *   - one own NodeState for outdated local ColumnData, and
  *   - ColumnDataChanges for each ColumnData that is outdated on remote
  *
  * this class essentially implements the NodeState sync protocol, which needs to be factored out since it is used
  * by both the UpstreamConnectorActor and the NodeServerRoute.
  *
  * Sync is always initiated by the child node since the server does not (need to) know child node IP addresses
  *
  */
class NodeStateResponder (node: Node,
                          logger: Loggable,
                          sendColumn: Column => Boolean,
                          receiveColumn: Column => Boolean,
                          sendRow: (Column,Row[_]) => Boolean,
                          receiveRow: (Column,Row[_]) => Boolean
                         ) {

  /**
    * get the series of replies that are required to sync the external NodeState with our own data
    * the exported function for which we exist
    */
  def getNodeStateReplies (extNodeState: NodeState): Seq[JsonSerializable] = {
    if (node.isKnownColumn(extNodeState.nodeId)) {
      val extOutdated = mutable.Buffer.empty[ColumnDatePair]
      val locOutdated = mutable.Buffer.empty[(String,Seq[RowDatePair])]
      val cdRowsToSend = mutable.Buffer.empty[(String,Seq[CellPair])]

      processExternalReadOnlyCDs( node, extNodeState,        extOutdated, cdRowsToSend)
      processExternalReadWriteCdRows( node, extNodeState,    locOutdated, cdRowsToSend)

      // TODO - what about CDs we export to remote that are not in the NS? (indicates outdated CLs)

      val replies = mutable.ArrayBuffer.empty[JsonSerializable]

      cdRowsToSend.foreach { e=>
        val (colId,cells) = e
        replies += ColumnDataChange( colId, node.id, node.columnDatas(colId).date, cells)
      }

      // we send the own NodeState last as the handshake terminator (even if there are no outdated own CDs)
      replies += NodeState(node, extOutdated.toSeq, locOutdated.toSeq)

      replies.toSeq

    } else {
      logger.warning(s"rejecting NodeState from unknown node ${extNodeState.nodeId}")
      Seq.empty[JsonSerializable]
    }
  }


  /**
    * for the given external readonly CD dates, accumulate the set of outdated own CDs and newer own CD rows to send
    *
    * since the other side only receives those CDs it is enough to go row-by-row for the outdated external CDs we
    * write and send to the external
    */
  protected def processExternalReadOnlyCDs(node: Node, extNodeState: NodeState,
                                           //-- output
                                           outdatedOwn: mutable.Buffer[ColumnDatePair],
                                           cdRowsToSend: mutable.Buffer[(String,Seq[CellPair])]): Unit = {
    extNodeState.readOnlyColumns.foreach { e=>
      val (colId, colDate) = e

      node.columnDatas.get(colId) match {
        case Some(cd) =>
          val col = node.columnList(colId)  // if we have a CD it also means we have a column for it

          if (colDate < cd.date) { // our data is newer
            if (sendColumn(col)) { // this is a column we write so we have to reply with all newer rows
              val cells = cd.changesSince(colDate,false).filter( cell=> sendRow(col, node.rowList(cell._1))) // TODO - equal date prioritization ?
              if (cells.nonEmpty) cdRowsToSend += (colId -> cells)
            }
            // otherwise we only receive this column and in this case the other node will send updates to our NodeState reply

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

  /**
    * for the given external read-write CD rows, accumulate the set of outdated own CDs and newer own CD rows to send
    */
  protected def processExternalReadWriteCdRows(node: Node, extNodeState: NodeState,
                                               //-- output
                                               outdatedOwn: mutable.Buffer[(String,Seq[RowDatePair])],
                                               cdRowsToSend: mutable.Buffer[(String,Seq[CellPair])]): Unit = {
    extNodeState.readWriteColumns.foreach { e=>
      val (colId,rowDates) = e

      node.columnDatas.get(colId) match {
        case Some(cd) =>
          val col = node.columnList(colId) // if we have a CD it also means we have a column for it
          val prioritizeOwn = col.node == node.id // do we own this column
          val addMissing = true

          if (sendColumn(col)) {
            val noc = cd.newerOwnCells(rowDates, addMissing, prioritizeOwn).filter( cell=> sendRow(col,node.rowList(cell._1)))
            if (noc.nonEmpty) cdRowsToSend += (colId -> noc)
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
