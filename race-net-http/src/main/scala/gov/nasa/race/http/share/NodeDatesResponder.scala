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

import gov.nasa.race.common.JsonSerializable
import gov.nasa.race.core.Loggable

import scala.collection.mutable

/**
  * object that responds to remote NodeDates messages with a sequence of
  *   - N ColumnDataChanges for each ColumnData that is outdated on remote
  *   - 1 ColumnReachabilityChange for currently online columns
  *   - 1 NodeDates for outdated local ColumnData
  *
  * this class essentially implements the NodeDates sync protocol, which needs to be factored out since it is used
  * by both the UpstreamConnectorActor and the NodeServerRoute.
  *
  * Sync is always initiated by the child node since the server does not (need to) know child node IP addresses
  *
  */
trait NodeDatesResponder {

  /**
    * get the series of replies that are required to sync the external NodeDates with our own data
    * the exported function for which we exist
    */
  def getNodeDatesReplies (node: Node, extNodeId: String, extNodeDates: NodeDates, logger: Loggable): Seq[JsonSerializable] = {
    //-- where we collect the reply data
    val extOutdated = mutable.Buffer.empty[ColumnDatePair] // CDs outdated on the remote node
    val ownOutdated = mutable.Buffer.empty[(String,Seq[RowDatePair])] // CD rows outdated on the local node
    val cdRowsToSend = mutable.Buffer.empty[(String,Seq[CellPair])]

    // for the given external readonly CD dates, accumulate the set of outdated own CDs and newer own CD rows to send.
    // since the other side only receives those CDs it is enough to go row-by-row for the outdated external CDs we
    // write and send to the external
    def processExternalReadOnlyCDs(): Unit = {
      extNodeDates.readOnlyColumns.foreach { e=>
        val (colId, colDate) = e

        node.columnDatas.get(colId) match {
          case Some(cd) =>
            val col = node.columnList(colId)  // if we have a CD it also means we have a column for it

            if (colDate < cd.date) { // our data is newer
              if (col.send.matches(extNodeId,col.owner,node)) { // this is a column we write so we have to reply with all newer rows
                val cells = cd.changesSince(colDate,false).filter { cell=>
                  val row = node.rowList(cell._1)
                  row.send.matches(extNodeId, col.owner, node)
                } // TODO - equal date prioritization ?
                if (cells.nonEmpty) cdRowsToSend += (colId -> cells)
              }
              // otherwise we only receive this column and in this case the other node will send updates to our NodeDates reply

            } else if (colDate > cd.date) { // remote data is newer
              if (col.receive.matches(extNodeId,col.owner,node)) {
                extOutdated += (colId -> cd.date)
              }
            }
          // since we only receive and don't modify this CD equal timestamps are treated as up-to-date

          case None => // we don't know this remote column
            logger.warning(s"unknown column ${e._1}")
        }
      }
    }

    // for the given external read-write CD rows, accumulate the set of outdated own CDs and newer own CD rows to send
    def processExternalReadWriteCdRows(): Unit = {
      extNodeDates.readWriteColumns.foreach { e=>
        val (colId,rowDates) = e

        node.columnDatas.get(colId) match {
          case Some(cd) =>
            val col = node.columnList(colId) // if we have a CD it also means we have a column for it
            val prioritizeOwn = col.owner == node.id  // it's our column
            val addMissing = true

            if (col.send.matches(extNodeId,col.owner,node)) {
              val noc = cd.newerOwnCells(rowDates, addMissing, prioritizeOwn).filter { cell=>
                val row = node.rowList(cell._1)
                row.send.matches(extNodeId,col.owner,node)
              }
              if (noc.nonEmpty) cdRowsToSend += (colId -> noc)
            }

            if (col.receive.matches(extNodeId,col.owner,node)) {
              val ooc = cd.outdatedOwnCells(rowDates, !prioritizeOwn).filter { rd=>
                val row = node.rowList(rd._1)
                row.receive.matches(extNodeId,col.owner,node)
              }
              if (ooc.nonEmpty) ownOutdated += (colId -> ooc)
            }

          case None => // we don't know this remote column
            logger.warning(s"unknown column ${e._1}")
        }
      }
    }


    if (node.isKnownColumn(extNodeDates.nodeId)) {
      // this computes the reply data
      processExternalReadOnlyCDs()
      processExternalReadWriteCdRows()

      // TODO - what about CDs we export to remote that are not in the NS? (indicates outdated CLs)

      val replies = mutable.ArrayBuffer.empty[JsonSerializable] // we send json objects

      cdRowsToSend.foreach { e=>
        val (colId,cells) = e
        replies += ColumnDataChange( colId, node.id, node.columnDatas(colId).date, cells)
      }

      replies += node.currentColumnReachability

      // we send the own NodeDates last as the handshake terminator (even if there are no outdated own CDs)
      replies += NodeDates(node, extOutdated.toSeq, ownOutdated.toSeq)

      replies.toSeq

    } else {
      logger.warning(s"rejecting NodeDates from unknown node ${extNodeDates.nodeId}")
      Seq.empty[JsonSerializable]
    }
  }
}
