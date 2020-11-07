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

import gov.nasa.race.http.tabdata.FormulaList.FormulaSpecs
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.ListMap


/**
  * site specific list of cell constraints
  *
  * cell constraints are ordinary Boolean cell formulas that are not used to set values but to flag violations.
  *
  * Note we re-use the CellExpression infrastructure here since respective BooleanCellExpressions are also useful to
  * set Boolean cell values. This will slightly increase the heap pressure by creating BooleanCellValue objects but
  * the functional overlap for an extensible constraint DSL would otherwise be too big
  */
class CellConstraintFormulaList(val id: Path, val info: String, val date: DateTime, val columnListId: Path, val rowListId: Path,
                                val formulaSpecs: Seq[(String,Seq[(String,(String,Option[String]))])] ) {

  // an ordered map of ordered maps: { col -> { row -> formula } }
  protected var columnFormulas: ListMap[Path,ListMap[Path,BooleanCellFormula]] = ListMap.empty

  // a list of [(col,[row->tt-formula])] entries
  protected var triggeredFormulas: Seq[(Column, Seq[(AnyRow,BooleanCellFormula)])] = Seq.empty

  def foreachEntry[U](f: ((Path,ListMap[Path,BooleanCellFormula]))=>U): Unit = columnFormulas.foreach(f)

  def hasAnyTimeTrigger: Boolean = triggeredFormulas.nonEmpty

  def timeTriggeredColumnFormulas: Seq[(Column, Seq[(AnyRow,BooleanCellFormula)])] = triggeredFormulas

  def evalTriggeredWith (ctx: EvalContext): Seq[ColumnDataConstraintViolations] = {
    timeTriggeredColumnFormulas.foldLeft(Seq.empty[ColumnDataConstraintViolations]){ (cdcvs, ce)=>
      val (col,tfs) = ce
      val violations = tfs.foldLeft(Seq.empty[(Path,BooleanCellFormula)]){ (violations,pe)=>
        val (row,cf) = pe
        if (cf.isTriggeredAt(ctx.evalDate)) {
          val cv = cf.evalWith(ctx)
          if (cv.value) violations :+ (row.id -> cf) else violations
        } else violations
      }
      if (violations.nonEmpty) {
        cdcvs :+ ColumnDataConstraintViolations( col.id, ctx.evalDate, violations)
      } else cdcvs
    }
  }

  def armTriggeredAt (refDate: DateTime): Unit = {
    timeTriggeredColumnFormulas.foreach( _._2.foreach( _._2.armTrigger(refDate)))
  }

  // TODO - the is too redundant with CellValueFormulaList
  def compileOn (nodeId: Path, columnList: ColumnList, rowList: RowList, funcLib: CellFunctionLibrary): Unit = {
    val compiler = new ConstraintExpressionParser(columnList,rowList,funcLib)

    formulaSpecs.foreach { e=>
      val colSpec = e._1
      val rowFormulaSrcs = e._2

      val cols = columnList.getMatchingColumns(nodeId,colSpec)

      if (cols.nonEmpty) {
        cols.foreach { col =>
          var tts = Seq.empty[(AnyRow,BooleanCellFormula)]
          var formulas = ListMap.empty[Path,BooleanCellFormula]

          rowFormulaSrcs.foreach { e =>
            val rowSpec = rowList.resolvePathSpec(e._1)
            val (src,ts) = e._2

            val rows = rowList.getMatchingRows(rowSpec)
            if (rows.nonEmpty) {
              rows.foreach { row =>
                val ce = compiler.compileBooleanExpr(col,row,src)
                val cf = new CellFormula(src,ts,ce)

                formulas = formulas + (row.id -> cf)
                if (cf.hasTimeTrigger) {
                  tts = tts :+ (row,cf)
                }
              }
            } else {
              // TODO - issue warning about non-matching row pattern
            }
          }

          if (tts.nonEmpty) {
            triggeredFormulas = triggeredFormulas :+ (col -> tts)
            tts = Seq.empty[(AnyRow,BooleanCellFormula)]
          }

          //formulas.foreach { e=> println(s"${e._1} : ${e._2.ce}")}
          columnFormulas = columnFormulas + (col.id -> formulas)
        }
      } else {
        // TODO - issue warning about non-matching col pattern
      }
    }
  }
}

class CellConstraintFormulaListParser extends FormulaListParser[CellConstraintFormulaList] {
  def createList (id: Path, info: String, date: DateTime, columnListId: Path, rowListId: Path, formulaSpecs: Seq[FormulaSpecs]) = {
    new CellConstraintFormulaList(id,info,date, columnListId, rowListId, formulaSpecs)
  }
}

class ConstraintExpressionParser (columnList: ColumnList, rowList: RowList, funcLib: CellFunctionLibrary)
                                                      extends CellExpressionParser (columnList, rowList, funcLib) {

  override def expr: Parser[AnyCellExpression] = {
    val ct = classOf[BooleanCellValue]

    fun(ct) | const(ct) | cellRef(ct)
  }

  /**
    * we don't check the expression type against the row since constraint formulas always have to be
    * BooleanCellExpressions
    */
  def compileBooleanExpr (col: Column, row: AnyRow, src: String): BooleanCellExpression = {
    compiledRow = row
    compiledColumn = col

    parseAll(expr, src) match {
      case Success (ce: AnyCellExpression,_) =>
        if (ce.cellType == classOf[BooleanCellValue]) {
          ce.asInstanceOf[BooleanCellExpression]
        } else {
          sys.error(s"constraint formula for '${col.id}'@'${row.id}' not a Boolean expression: $src")
        }

      case Failure(msg,_) => sys.error(s"formula '$src' failed to compile: $msg")
      case Error(msg,_) => sys.error(s"error compiling formula '$src': $msg")
      case other => sys.error(s"unexpected parse result: $other")
    }
  }
}