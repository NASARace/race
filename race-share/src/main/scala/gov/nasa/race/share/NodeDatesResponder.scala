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

import gov.nasa.race.common.JsonSerializable
import gov.nasa.race.core.Loggable
import gov.nasa.race.uom.DateTime

import scala.collection.mutable

/**
  * mix-in that computes ColumnDataChanges for external NodeDates objects
  *
  */
trait NodeDatesResponder {

  def getColumnDataChange (extNodeId: String, colId: String, extDate: DateTime)(implicit node: Node): Option[ColumnDataChange] = {
    node.columnList.get(colId).flatMap { col=>
      if (col.isSentTo(extNodeId)) {
        node.columnDatas.get(colId).flatMap { cd=>
          val cvs = cd.filteredCellPairs{ (rowId,cv) =>
            node.rowList.get(rowId) match {
              case Some(row) => (cv.date > extDate) && row.isSentTo(extNodeId)(node,col)
              case None => false // unknown row
            }
          }
          if (cvs.nonEmpty) Some( ColumnDataChange(colId, node.id, cd.date, cvs) ) else None
        }
      } else None // we don't send this column to extNodeId
    }
  }

  def getColumnDataRowChange (extNodeId: String, colId: String, extCvDates: Seq[(String,DateTime)])(implicit node: Node): Option[ColumnDataChange] = {
    node.columnList.get(colId).flatMap { col=>
      if (col.isSentTo(extNodeId)) {
        node.columnDatas.get(colId).flatMap { cd=>
          val seenRows = mutable.Set.empty[String]
          val cvs = mutable.Buffer.empty[CellPair]

          //--- add external node CVs that are outdated
          extCvDates.foreach { e=>
            val rowId = e._1
            val extDate = e._2
            seenRows += rowId

            node.rowList.get(rowId).foreach { row=>
              cd.get(rowId).foreach { cv=>
                  if (cv.date > extDate && row.isSentTo(extNodeId)(node,col)) cvs += (rowId -> cv)
              }
            }
          }

          //--- add external node CVs that are missing
          cd.foreach { (rowId,cv) =>
            if (!seenRows.contains(rowId)) {
              node.rowList.get(rowId).foreach { row=>
                if (row.isSentTo(extNodeId)(node,col)) cvs += (rowId -> cv)
              }
            }
          }

          if (cvs.nonEmpty) Some( ColumnDataChange(colId, node.id, cd.date, cvs.toSeq) ) else None
        }
      } else None // we don't send this column to extNodeId
    }
  }

  def getColumnDataChanges (extNodeDates: NodeDates, node: Node): Seq[ColumnDataChange] = {
    val newerOwn: mutable.Buffer[ColumnDataChange] = mutable.Buffer.empty
    val extNodeId = extNodeDates.nodeId

    extNodeDates.columnDataDates.foreach( e=> getColumnDataChange(extNodeId,e._1,e._2)(node).foreach(newerOwn.addOne))
    extNodeDates.columnDataRowDates.foreach( e=> getColumnDataRowChange(extNodeId,e._1,e._2)(node).foreach(newerOwn.addOne))

    newerOwn.toSeq
  }
}
