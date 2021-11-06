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

import gov.nasa.race.uom.DateTime
import gov.nasa.race.{Failure, Result, Success}

import scala.collection.immutable.Map
import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ClassTag, classTag}

/**
  * abstraction of the data model used to evaluate CellExpressions and to create CellValues
  *
  * note this interface is used for both read and write operations so that changes can be accumulated before
  * they are published
  *
  * note also that it is up to the implementor to handle undefined cell values for valid row ids so that we can
  * support sparse cell value containers
  */
trait EvalContext {
  def id: String         // where we evaluate

  def evalDate: DateTime // used to create new CellValue instancesds
  def rowList: RowList

  //--- methods to keep track of changes
  def addChange (colId: String, row: Row[_], cv: CellValue[_]): Unit
  def hasChanges: Boolean
  def clearChanges(): Unit
  def foreachChange (f: (String,Row[_],CellValue[_])=>Unit): Unit
  def existsChange (f: (String,Row[_],CellValue[_])=>Boolean): Boolean
  def foldLeftChanges [T] (acc: T)(f: (T, String,Row[_],CellValue[_])=>T): T
  def foldRightChanges [T] (acc: T)(f: (T, String,Row[_],CellValue[_])=>T): T

  /**
    * cell value getter
    */
  def getCellValue (colId: String, rowId: String): Option[CellValue[_]]  // abstract accessor for CellValues
  def getCellValue (cellRef: CellRef[_]): Option[CellValue[_]] = getCellValue(cellRef.colId, cellRef.rowId)

  def getColumnData(colId: String): Option[ColumnData]


  //--- generic convenience methods
  // use these in a context that has already checked colId/rowId against ColumnList/RowList instances

  def cellValue (colId: String, rowId: String): CellValue[_] = {
    getCellValue(colId, rowId).getOrElse( throw new NoSuchElementException(s"$colId:$rowId"))
  }

  def typedCellValue [T <: CellValue[_] :ClassTag] (colId: String, rowId: String): T = {
    cellValue(colId,rowId) match {
      case cv:T => cv
      case _ => throw new RuntimeException(s"invalid row type: '$rowId' not a ${classTag[T].runtimeClass.getSimpleName} row")
    }
  }

  def cellValues (colId: String): ColumnData = {
    getColumnData(colId) match {
      case Some(cd) => cd
      case _ => throw new NoSuchElementException(s"unknown column $colId")
    }
  }

  def hasCellValue (colId: String, rowId: String): Boolean = {
    getCellValue(colId, rowId) match {
      case Some(_: UndefinedCellValue[_]) | None => false
      case _ => true
    }
  }
  def hasCellValue (cellRef: CellRef[_]): Boolean = hasCellValue(cellRef.colId, cellRef.rowId)

  def getTypedCellValue [T <: CellValue[_] :ClassTag] (colId: String, rowId: String): Option[T] = {
    getCellValue(colId,rowId) match {
      case Some(cv: T) => Some(cv)
      case _ => None
    }
  }
}


/**
  * a mutable EvalContext that keeps ColumnDatas in a var that holds an immutable ColumnData map
  *
  * we keep this separate from Node so that we can batch-mutate and assess changes before they are published
  *
  * note we do use the ColumnData property that valid rowIds always return (possibly undefined) cell values, only
  * invalid rowIds can cause NoSuchElementExceptions
  */
trait MutableEvalContext extends EvalContext {

  var columnDatas: Map[String,ColumnData]  // this is what gets mutated (in addition to evalDate)

  protected val changes: ArrayBuffer[(String,Row[_],CellValue[_])] = ArrayBuffer.empty

  def addChange (colId: String, row: Row[_], cv: CellValue[_]): Unit = {
    val newChange = new Tuple3[String,Row[_],CellValue[_]](colId,row,cv)
    changes += newChange // compiler does not recognize tuple construction otherwise
  }
  def hasChanges: Boolean = changes.nonEmpty
  def clearChanges(): Unit = changes.clear()
  def foreachChange (f: (String,Row[_],CellValue[_])=>Unit): Unit = changes.foreach( e=> f(e._1,e._2,e._3))
  def existsChange (f: (String,Row[_],CellValue[_])=>Boolean): Boolean = changes.exists( e=> f(e._1,e._2,e._3))
  def foldLeftChanges [T] (acc: T)(f: (T, String,Row[_],CellValue[_])=>T): T = changes.foldLeft(acc)( (acc,e)=> f(acc,e._1,e._2,e._3) )
  def foldRightChanges [T] (acc: T)(f: (T, String,Row[_],CellValue[_])=>T): T = changes.foldRight(acc)( (e,acc)=> f(acc,e._1,e._2,e._3) )

  override def cellValue (colId: String, rowId: String): CellValue[_] = columnDatas.get(colId) match {
    case Some(cd) => cd(rowList(rowId))
    case None => throw new NoSuchElementException(s"unknown column $colId")
  }

  override def getCellValue(colId: String, rowId: String): Option[CellValue[_]] = {
    columnDatas.get(colId).flatMap( _.get(rowId))
  }

  override def getColumnData(colId: String): Option[ColumnData] = columnDatas.get(colId)

  // override if we have to keep track of changes
  protected def updateColumnData (oldCd: ColumnData, changedCvs: Seq[(String,CellValue[_])]): ColumnData = {
    oldCd.updateCvs(rowList,changedCvs,evalDate)
  }

  def setCellValues (colId: String, changedCvs: Seq[(String,CellValue[_])]): Result = {
    columnDatas.get(colId) match {
      case Some(oldCd) =>
        try {
          val newCd = oldCd.updateCvs(rowList, changedCvs, evalDate, addChange)
          if (newCd ne oldCd) {
            columnDatas = columnDatas + (colId -> newCd)
            Success
          } else Failure(s"rejected outdated cell values $changedCvs")
        } catch {
          case x: Throwable => Failure(x.getMessage)
        }

      case None => Failure(s"invalid column $colId")
    }
  }

  def setCellValue (colId: String, rowId: String, cv: CellValue[_]): Result = {
    columnDatas.get(colId) match {
      case Some(oldCd) =>
        try {
          val newCd = oldCd.updateCv(rowList, rowId,cv,evalDate, addChange)
          if (newCd ne oldCd) {
            columnDatas = columnDatas + (colId -> newCd)
            Success
          } else Failure(s"rejected outdated cell value $rowId: $cv")
        } catch {
          case x: Throwable => Failure(x.getMessage)
        }

      case None => Failure(s"invalid column $colId")
    }
  }
}


/**
  * basic RecordingEvalContext implementation
  */
class BasicEvalContext (val id: String, val rowList: RowList, var evalDate: DateTime, var columnDatas: Map[String,ColumnData]) extends MutableEvalContext {
  def this (node: Node, evalDate: DateTime) = this(node.id, node.rowList, evalDate, node.columnDatas)
}