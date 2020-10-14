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

import java.io.File
import java.nio.file.Path

import com.typesafe.config.Config
import gov.nasa.race.common.{JsonWriter, UnixPath}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, RaceException, SubscribingRaceActor}
import gov.nasa.race.ifSome
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import scala.collection.immutable.ListMap
import scala.collection.mutable

object TabDataUpdateActor {

  def dumpColumnData (columnList: ColumnList, rowList: RowList, cm: collection.Map[Path,ColumnData]): Unit = {
    print("row                  |")
    columnList.foreach( e=> print(f"${e._2.name}%15.15s |") )
    println()
    print("---------------------+") // row name
    var i = columnList.size
    while (i > 0) { print("----------------+"); i-= 1 }
    println()

    rowList.foreach { r =>
      val row = r._2
      print(f"${row.id}%-20.20s |")
      columnList.foreach { c =>
        val cd: ColumnData = cm(c._1)
        cd.get(row.id) match {
          case Some(cv) =>  print(f"${cv.valueToString}%15.15s |")
          case None => print("              - |")
        }
      }
      println()
    }
  }
}


/**
  * the actor that initializes and updates our node data model, both static (node id, column/rowList, formulas) and
  * dynamic (ColumnData). We publish both the changed data (ColumnData) and the respective change event (ColumnDataChange)
  *
  * the formula evaluation logic is mostly contained in the EvalContext trait, which is used from respective
  * CellFormula/CellExpression instances
  *
  * this actor should not be concerned about up- or downStream connections, connection-types (such as web sockets) or
  * serialization formats (JSON)
  */
class TabDataUpdateActor(val config: Config) extends SubscribingRaceActor with PublishingRaceActor with EvalContext {

  //--- the data model
  val nodeId: Path = UnixPath.intern(config.getString("node-id"))
  val upstreamId: Option[Path] = config.getOptionalString("upstream-id").map(UnixPath.intern) // TODO - do we need this here?

  var rowList: RowList = readRowList
  var columnList: ColumnList = readColumnList
  val columnData: mutable.Map[Path,ColumnData] = readColumnData(rowList,columnList)

  var formulaList: Option[FormulaList] = readFormulaList
  val funcLib: CellFunctionLibrary = getConfigurableOrElse("functions")(new BasicFunctionLibrary)


  ifSome(formulaList){_.compileOn(nodeId,columnList,rowList,funcLib)}
  initColumnData

  // for debugging purposes
  def getCell (col: Path, row: Path): Option[CellValue] = {
    for (
      cd <- columnData.get(col);
      cv <- cd.get(row)
    ) yield cv
  }

  def getColumnCells (pCol: Path): Map[Path,CellValue] = {
    columnData.get(pCol) match {
      case Some(cd) => cd.rows
      case None => Map.empty
    }
  }

  //--- actor interface

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    if (super.onInitializeRaceActor(raceContext,actorConf)) {
      val site = Node(nodeId, rowList, columnList, upstreamId)
      publish(site) // publish the (mostly) static node data
      columnData.foreach(e => publish(e._2)) // publish the dynamic data
      true
    } else false
  }

  override def handleMessage: Receive = {
    case BusEvent(_, pdc:ColumnDataChange, _) => processColumnDataChange(pdc)
  }

  //--- data model init

  protected def readList[T](key: String)(f: Array[Byte]=>Option[T]): Option[T] = {
    info(s"reading $key from file ${config.getString(key)}")
    config.translateFile(key)(f)
  }

  def readRowList: RowList = {
    readList[RowList]("row-list") { input =>
      val parser = new RowListParser
      parser.setLogging(info, warning, error)
      parser.parse(input)
    }.getOrElse( throw new RuntimeException("missing or invalid row-list"))
  }

  def readColumnList: ColumnList = {
    readList[ColumnList]("column-list"){ input=>
      val parser = new ColumnListParser
      parser.setLogging(info,warning,error)
      parser.parse(input)
    }.getOrElse( throw new RuntimeException("missing or invalid column-list"))
  }

  def readFormulaList: Option[FormulaList] = {
    readList[FormulaList]("formula-list"){ input=>
      val parser = new FormulaListParser
      parser.setLogging(info,warning,error)
      parser.parse(input)
    }
  }

  def readColumnData (rowList: RowList, columnList: ColumnList): mutable.Map[Path,ColumnData] = {
    val map = mutable.Map.empty[Path,ColumnData]

    val dataDir = config.getExistingDir("column-data")
    for (p <- columnList.columns) {
      val col = p._2
      val colName = col.name
      val f = new File(dataDir, s"$colName.json")
      info(s"reading column data for '$colName' from $f")
      if (f.isFile) {
        val parser = new ColumnDataParser(rowList)
        parser.setLogging(info,warning,error)
        parser.parse(FileUtils.fileContentsAsBytes(f).get) match {
          case Some(columnData) =>
            map += col.id -> columnData
          case None =>
            throw new RaceException(f"error parsing column data in $f")
        }
      } else {
        warning(s"no column data for '$colName'")
        // init with Date0 so that we always try to update from remote
        map += col.id -> ColumnData(col.id,DateTime.Date0,columnList.id,rowList.id,Map.empty[Path,CellValue])
      }
    }

    map
  }

  //--- data model update

  /**
    * evaluate the given column with the given set of row formulas
    * the result is obtained via our EvalContext implementation
    */
  def evalCurrentColumn(formulas: ListMap[Path,AnyCellFormula], date: DateTime)(cellUpdateCond: AnyCellFormula=>Boolean): Unit = {
    setEvalDate(date)

    formulas.foreach { e =>
      val rowId = e._1
      val formula = e._2

      rowList.get(rowId) match {
        case Some(row) =>
          setCurrentRow(row)
          if (cellUpdateCond(formula)) {
            val cellVal = formula.evalWith(this)
            setCurrentCellValue(cellVal)
          }
        case None => warning(s"unknown row: $rowId")
      }
    }
  }

  /**
    * eval undefined cells for which we have formulas
    */
  def initColumnData: Unit = {
    ifSome(formulaList) {
      _.foreachEntry { fe =>
        val pCol = fe._1
        val formulas = fe._2 // the formulas for this column

        columnList.get(pCol) match {
          case Some(col: Column) => // a column we know
            setCurrentColumn(col)
            evalCurrentColumn(formulas, DateTime.now) { formula => !currentCellValue.isDefined && formula.canEvaluateIn(this) }

            if (hasChangedCells) {
              columnData += (pCol -> columnDataFromCurrentCells)
              // nothing to publish yet, this is called during init
            }

          case None => // nothing to compute
        }
      }
    }
  }

  def applyChangesToCurrentColumn (changedCells: Seq[Cell]): Unit = {
    changedCells.foreach { cell =>
      val (pRow, newCellValue) = cell
      rowList.get(pRow) match {
        case Some(row) =>
          setCurrentRow(row)
          if (currentCellValue.date < newCellValue.date) {
            setCurrentCellValue(newCellValue)
          }
        case None => info(s"ignore change to unknown row $pRow")
      }
    }
  }

  // this should always go together
  def updateAndPublishColumnData (cd: ColumnData): Unit = {
    columnData += (cd.id -> cd)
    publish(cd)
  }

  /**
    * main entry point for both local and remote data changes
    *
    * we split changes in external/own (eval) and report them as separate CDCs (with potentially different
    * changeNodeIds)
    */
  def processColumnDataChange(cdc: ColumnDataChange): Unit = {
    if (updateChangeColumnData(cdc)) {
      updateOtherColumnData(cdc)
    }
  }

  def updateChangeColumnData (cdc: ColumnDataChange): Boolean = {
    val pCdc = cdc.columnId

    //--- first, apply and publish the CDC changes and eval changes that might be triggered by them
    columnList.get(pCdc) match { // a known column
      case Some(col) =>
        columnData.get(pCdc) match {
          case Some(cd) =>
            info(s"update column '$pCdc' with $cdc")
            if (cd.date < cdc.date) {
              setCurrentColumn(col)
              applyChangesToCurrentColumn(cdc.cells)

              if (hasChangedCells) {
                ifSome(formulaList){ fl=>
                  clearChangedCells
                  val evalDate = if (cdc.changeNodeId == nodeId) cdc.date else DateTime.now
                  evalCurrentColumn(fl.getColumnFormulas(pCdc), evalDate) { _.hasUpdatedDependencies(this) }
                  updateAndPublishColumnData( columnDataFromCurrentCells)
                  distributeColumnDataChanges(cdc,changedCellSeq,evalDate) // publish ColumnDataChanges to other processes/nodes
                }
                true

              } else {
                if (cd.date < cdc.date) {
                  updateAndPublishColumnData( cd.copy(date = cdc.date))
                  // TODO - should we distribute this to other nodes?
                }
                false
              }

            } else { // our data is newer or same date

              if (cd.date > cdc.date) { // ours is newer, send CDC
                warning(s"outdated change of $pCdc")
                val updates = cd.changesSince(cdc.date)
                if (updates.nonEmpty) {
                  publish(ColumnDataChange(pCdc,nodeId,cd.date,updates))
                }

              } else { // same date - check if these were our changes
                if (cdc.changeNodeId == nodeId) { // this is our data, check
                  val diffs = cd.differingCellValues(cdc)
                  if (diffs.nonEmpty) {
                    warning(s"feedback cell values of $pCdc differ: $diffs")
                  } else {
                    info(s"change feedback confirmed: $pCdc")
                  }
                }
              }

              false // we didn't change our cell values
            }
          case None => error(s"no ColumnData for column $pCdc"); false
        }
      case None => info(s"ignoring ColumnDataChange for unknown column $pCdc"); false
    }
  }

  def updateOtherColumnData (cdc: ColumnDataChange): Unit = {
    ifSome(formulaList){ fl=>
      fl.foreachEntry { fe =>
        val (pCol,formulas) = fe
        if (pCol != cdc.columnId) { // for all but the CDC column, which was already handled
          columnList.get(pCol) match {
            case Some(col: Column) => // a column we know
              val evalTime = DateTime.now
              setCurrentColumn(col)
              evalCurrentColumn(formulas, evalTime) { _.hasUpdatedDependencies(this) }
              if (hasChangedCells) {
                updateAndPublishColumnData(columnDataFromCurrentCells)
                // distribute as our own CDC
                val ownCdc = ColumnDataChange(pCol, nodeId, evalTime, changedCellSeq)
                distributeColumnDataChanges(ownCdc, Seq.empty, evalTime)
              }

            case None => // ignore unknown column
          }
        }
      }
    }
  }

  /**
    * send changes to external nodes (both upstream and downstream)
    */
  def distributeColumnDataChanges (cdc: ColumnDataChange, extraChanges: Seq[Cell], evalDate: DateTime): Unit = {

    def ownChanges (cdc: ColumnDataChange, evalChanges: Seq[Cell], evalDate: DateTime): ColumnDataChange = {
      if (evalChanges.isEmpty && cdc.changeNodeId == nodeId && evalDate == cdc.date) {
        cdc
      } else {
        cdc.copy(changeNodeId = nodeId, date = evalDate, cells = evalChanges)
      }
    }

    def coalesceChanges (cdc: ColumnDataChange, evalChanges: Seq[Cell], evalDate: DateTime): ColumnDataChange = {
      if (evalChanges.nonEmpty) {
        cdc.copy(changeNodeId = nodeId, date = evalDate, cells = cdc.cells ++ evalChanges)
      } else cdc
    }

    columnList.get(cdc.columnId) match {
      case Some(col) =>
        if (cdc.changeNodeId == nodeId) { // cdc originated locally, coalesce with eval changes before publishing
          publish( coalesceChanges(cdc,extraChanges,evalDate))

        } else { // change originated remotely, send remote and local eval changes as separate events
          publish(cdc)
          if (extraChanges.nonEmpty) {
            publish(ownChanges(cdc, extraChanges, evalDate))
          }
        }

      case None => warning(s"not a known column: ${cdc.columnId}")
    }
  }

  def isUpstream (id: Path): Boolean = (upstreamId.isDefined && upstreamId.get == id)

  def isColumn (id: Path): Boolean = columnList.columns.contains(id)

  /**
    * is this from a known node
    */
  def isValidChange (cdc: ColumnDataChange): Boolean = {
    ((cdc.changeNodeId == nodeId ) || isUpstream(cdc.changeNodeId) || isColumn(cdc.changeNodeId))
  }
}
