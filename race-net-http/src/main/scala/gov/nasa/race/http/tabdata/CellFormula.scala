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
import gov.nasa.race.common.{JsonParseException, TimeTrigger, UTF8JsonPullParser, UnixPath}
import gov.nasa.race.http.tabdata.FormulaList.FormulaSpecs
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

/**
  * object managing CellExpressions and associated cell dependencies
  */
class CellFormula [+T <: CellValue](val src: String, val triggerSrc: Option[String], val ce: CellExpression[T]) {

  private val dependencies: Set[CellRef[_]] = ce.dependencies()
  private val trigger: Option[TimeTrigger] = triggerSrc.map(TimeTrigger.apply)

  def evalWith (ctx: EvalContext): T = ce.eval(ctx)
  def hasDependencies: Boolean = dependencies.nonEmpty
  def dependsOn (cref: CellRef[_]): Boolean = dependencies.contains(cref)

  def dependsOnAny (pCol: Path, cells: scala.collection.Seq[Cell]): Boolean = {
    cells.exists { c=>
      dependencies.exists(cr => cr.col == pCol && cr.row == c._1)
    }
  }

  def canEvaluateIn (ctx: EvalContext): Boolean = {
    dependencies.forall(ctx.cell(_).isDefined)
  }

  def hasUpdatedDependencies (ctx: EvalContext): Boolean = {
    val curDate = ctx.currentCellValue.date
    dependencies.exists(ctx.cell(_).date > curDate)
  }

  def hasNewerDependencies (ctx: EvalContext, date: DateTime): Boolean = {
    dependencies.exists(ctx.cell(_).date > date)
  }

  //--- time triggered formulas

  def hasTimeTrigger: Boolean = trigger.isDefined

  def isTriggeredAt (d: DateTime): Boolean = {
    trigger.isDefined && trigger.get.check(d)
  }

  def armTrigger (refDate: DateTime): Unit = {
    if (trigger.isDefined) trigger.get.armCheck(refDate)
  }
}

/**
  * a named collection of (columnPattern -> (rowPattern -> cellFormula)) specs
  */
class CellValueFormulaList(val id: Path, val info: String, val date: DateTime, val columnListId: Path, val rowListId: Path,
                           val formulaSpecs: Seq[(String,Seq[(String,(String,Option[String]))])] ) {

  // an ordered map of ordered maps: { col -> { row -> formula } }
  protected var columnFormulas: ListMap[Path,ListMap[Path,AnyCellFormula]] = ListMap.empty

  // a list of [(col,[row->tt-formula])] entries
  protected var triggeredFormulas: Seq[(Column, Seq[(AnyRow,AnyCellFormula)])] = Seq.empty

  def compileOn (nodeId: Path, columnList: ColumnList, rowList: RowList, funcLib: CellFunctionLibrary): Unit = {
    val compiler = new CellExpressionParser(columnList,rowList,funcLib)

    formulaSpecs.foreach { e=>
      val colSpec = e._1
      val rowFormulaSrcs = e._2

      val cols = columnList.getMatchingColumns(nodeId,colSpec)

      if (cols.nonEmpty) {
        cols.foreach { col =>
          var tts = Seq.empty[(AnyRow,AnyCellFormula)]
          var formulas = ListMap.empty[Path,AnyCellFormula]

          rowFormulaSrcs.foreach { e =>
            val rowSpec = rowList.resolvePathSpec(e._1)
            val (src,ts) = e._2

            val rows = rowList.getMatchingRows(rowSpec)
            if (rows.nonEmpty) {
              rows.foreach { row =>
                val ce = compiler.compile(col,row,src)
                val cf = new CellFormula(src,ts,ce)

                formulas = formulas + (row.id -> cf)
                if (cf.hasTimeTrigger) {
                   tts = tts :+ (row,cf)
                }
              }
            }
          }

          if (tts.nonEmpty) {
            triggeredFormulas = triggeredFormulas :+ (col -> tts)
            tts = Seq.empty[(AnyRow,AnyCellFormula)]
          }

          //forms.foreach { e=> println(s"${e._1} : ${e._2.ce}")}
          columnFormulas = columnFormulas + (col.id -> formulas)
        }
      }
    }
  }

  def getColumnFormulas(pCol: Path): ListMap[Path,AnyCellFormula] = columnFormulas.getOrElse(pCol,ListMap.empty)

  def foreachEntry[U](f: ((Path,ListMap[Path,AnyCellFormula]))=>U): Unit = columnFormulas.foreach(f)

  def hasAnyTimeTrigger: Boolean = triggeredFormulas.nonEmpty

  def timeTriggeredColumnFormulas: Seq[(Column, Seq[(AnyRow,AnyCellFormula)])] = triggeredFormulas

  def evalTriggeredWith (ctx: EvalContext): Seq[ColumnDataChange] = {
    timeTriggeredColumnFormulas.foldLeft(Seq.empty[ColumnDataChange]){ (cdcs,ce)=>
      val (col,tfs) = ce
      val changes = tfs.foldLeft(Seq.empty[(Path,CellValue)]){ (changes,pe)=>
        val (row,cf) = pe
        if (cf.isTriggeredAt(ctx.evalDate)) {
          val cv = cf.evalWith(ctx)
          changes :+ (row.id -> cv)
        } else changes
      }
      if (changes.nonEmpty) {
        cdcs :+ ColumnDataChange( col.id, ctx.nodeId, ctx.evalDate, changes)
      } else cdcs
    }
  }

  def armTriggeredAt (refDate: DateTime): Unit = {
    timeTriggeredColumnFormulas.foreach( _._2.foreach( _._2.armTrigger(refDate)))
  }
}

object FormulaList {
  val _id_        = asc("id")
  val _info_      = asc("info")
  val _date_      = asc("date")
  val _columnListId_ = asc("columnlist")
  val _rowListId_ = asc("rowlist")
  val _columns_   = asc("columns")
  val _src_       = asc("src")
  val _trigger_   = asc("trigger")

  type FormulaSpecs = (String , Seq[(String , (String,Option[String]))])
}

/**
  * a parser for json representations of CellFormulaList instances
  */
trait FormulaListParser[T] extends UTF8JsonPullParser {
  import FormulaList._

  def createList (id: Path, info: String, date: DateTime, columnListId: Path, rowListId: Path, formulaSpecs: Seq[FormulaSpecs]): T

  def parse (buf: Array[Byte]): Option[T] = {
    initialize(buf)

    try {
      readNextObject {
        val id = UnixPath.intern(readQuotedMember(_id_))
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        val columnListId = UnixPath.intern(readQuotedMember(_columnListId_))
        val rowListId = UnixPath.intern(readQuotedMember(_rowListId_))

        val formulaSpecs = readNextObjectMemberInto(_columns_, ArrayBuffer.empty[FormulaSpecs]) {
          val colSpec = readObjectMemberName().toString
          val formulas = readCurrentObjectInto(ArrayBuffer.empty[(String,(String,Option[String]))]) {
            val rowSpec = readObjectMemberName().toString
            val formulaSrc = readQuotedMember(_src_).toString
            val triggerSrc = readOptionalQuotedMember(_trigger_).map(_.toString)

            skipPastAggregate()
            (rowSpec,(formulaSrc,triggerSrc))
          }.toSeq
          (colSpec,formulas)
        }.toSeq

        Some(createList(id,info,date, columnListId, rowListId, formulaSpecs))
      }
    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed rowList: ${x.getMessage}")
        None
    }
  }
}

class CellValueFormulaListParser extends FormulaListParser[CellValueFormulaList] {
  def createList (id: Path, info: String, date: DateTime, columnListId: Path, rowListId: Path, formulaSpecs: Seq[FormulaSpecs]) = {
    new CellValueFormulaList(id,info,date, columnListId, rowListId, formulaSpecs)
  }
}