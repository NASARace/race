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

import gov.nasa.race.common.UnixPath
import gov.nasa.race.uom.DateTime

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


/**
  * aggregate that contains what is needed from the environment to evaluate CellExpressions
  *
  * changes are processed by column
  * TODO - shall we make EvalContext re-usable over several columns?
  *
  * this is an internal, non-threadsafe object which is mutated during processing of a CDC.
  * After the CDC has been processed, cells contains the new values for the respective column,
  * and changes contains a list of the changed FieldValues/Field paths
  */
trait EvalContext {

  // the data model interface
  def nodeId: Path
  def columnList: ColumnList
  def rowList: RowList
  def getColumnCells (pCol: Path): Map[Path,CellValue]

  private var _evalDate: DateTime = DateTime.UndefinedDateTime // timestamp to use for new Cell values

  // our internal data for sequential column/row changes
  private var _currentColumn: Column = null // currently changed column
  private var _currentRow: AnyRow = null       // currently changed row

  private var _currentCells: Map[Path,CellValue] = Map.empty // currently changed Cells ColumnData
  private val _changedCells = new ArrayBuffer[Cell].empty  // accumulated changes of currentCells, in order

  def haveCurrentCellsChanged: Boolean = (_currentCells.nonEmpty && (_currentCells ne getColumnCells(_currentColumn.id)))
  def hasChangedCells: Boolean = _changedCells.nonEmpty
  def clearChangedCells: Unit = _changedCells.clear()

  def currentCells: Map[Path,CellValue] = _currentCells
  def changedCells: scala.collection.Seq[Cell] = _changedCells
  def changedCellSeq: Seq[Cell] = _changedCells.toSeq

  def setEvalDate (d: DateTime): Unit = _evalDate = d
  def evalDate: DateTime = _evalDate

  def setCurrentColumn (col: Column): Unit = {
    if (columnList.contains(col.id)) {
      _currentColumn = col
      _currentRow = null
      _currentCells = getColumnCells(col.id)
      _changedCells.clear()
    } else throw new IllegalArgumentException(s"not a valid column: ${col.id}")
  }

  def currentColumn: Column = _currentColumn
  def currentRow: Row[_] = _currentRow

  def setCurrentRow (row: AnyRow): Unit = {
    if (rowList.contains(row.id)) {
      _currentRow = row
    } else throw new IllegalArgumentException(s"not a valid row: ${row.id}")
  }

  def setCurrentCellValue (newValue: CellValue): Unit = {
    if (_currentRow == null) throw new RuntimeException("uninitialized EvalContext")
    val cell = (_currentRow.id -> newValue)
    _currentCells = _currentCells + cell
    _changedCells += cell
  }

  def currentCellValue: CellValue = {
    if (_currentRow == null) throw new RuntimeException("uninitialized EvalContext")

    _currentCells.get(_currentRow.id) match {
      case Some(c) => c
      case None => _currentRow.undefinedCellValue
    }
  }

  def columnDataFromCurrentCells: ColumnData = ColumnData(_currentColumn.id, _evalDate, columnList.id, rowList.id, _currentCells)

  /**
    * note that the CellExpression compiler should already have normalized path references
    */
  def cell (c: Path, r: Path): CellValue = {
    if (UnixPath.isCurrent(c) || c == _currentColumn.id) { // current column - look up value in currentCells
      if (UnixPath.isCurrent(r)) currentCellValue
      else _currentCells.getOrElse(_currentRow.resolve(r), _currentRow.undefinedCellValue)

    } else { // not the current column
      getColumnCells(_currentColumn.resolve(c)).getOrElse(_currentRow.resolve(r), _currentRow.undefinedCellValue)
    }
  }

  def cell (cref: CellRef[_]): CellValue = cell(cref.col,cref.row)
}

/**
  * mostly for testing purposes, by-passing ColumnData
  */
class BasicEvalContext ( val nodeId: Path,
                         val columnList: ColumnList,
                         val rowList: RowList,
                         val columnData: mutable.Map[Path,Map[Path,CellValue]]
                       ) extends EvalContext {

  def getColumnCells (pCol: Path): Map[Path,CellValue] = columnData.getOrElse(pCol, Map.empty)
}