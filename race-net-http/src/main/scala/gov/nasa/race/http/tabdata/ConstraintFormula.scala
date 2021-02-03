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

import gov.nasa.race.common.{JsonParseException, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime
import FormulaList._
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.{Failure, Result, ResultValue, Success, SuccessValue, ifSome}

import scala.collection.mutable.ArrayBuffer

object ConstraintFormula {
  def compile (baseCol: Column, id: String, info: String, src: String, level: Int, mark: Option[String], node: Node, funLib: CellFunctionLibrary): ResultValue[ConstraintFormula] = {
    val parser = new ConstraintFormulaParser(node, baseCol, funLib)
    parser.compile(src).flatMap { expr =>
      if (expr.isInstanceOf[BoolExpression]) {
        val assoc = mark match {
          case Some(crSpecs) =>
            parser.parseCellRefs(crSpecs) match {
              case SuccessValue(crs) => crs
              case err@Failure(msg) => return err
            }
          case None => Set.empty[CellRef[_]]
        }

        SuccessValue( new ConstraintFormula(id,info,src,expr.asInstanceOf[BoolExpression],level,assoc))

      } else Failure("constraint not a boolean cell formula")
    }
  }
}

/**
  * a CellFormula that is used to check cell value properties
  *
  * if a formula evaluates to false we report the property as violated
  */
case class ConstraintFormula (id: String, info: String, src: String,  expr: BoolExpression, level: Int, assoc: Set[CellRef[_]]) extends CellFormula[Boolean] {
  def eval (ctx: EvalContext): Boolean = expr.eval(ctx)
}

/**
  * a specialized CellExpressionParser for expressions that are not compiled in the context of a given column or row
  */
class ConstraintFormulaParser (node: Node, columnBase: Column, funLib: CellFunctionLibrary) extends CellExpressionParser(funLib) {

  protected def _matchRows (rowSpec: String): Seq[String] = {
    val rowList = node.rowList

    val rs = PathIdentifier.resolve(rowSpec,rowList.id)
    if (PathIdentifier.isGlobPattern(rowSpec)) {
      rowList.matching(rowSpec).map(_.id).toSeq
    } else {
      if (rowList.contains(rs)) Seq(rs) else sys.error(s"reference of unknown row $rs")
    }
  }

  protected def _matchColumns (colSpec: String): Seq[String] = {
    val columnList = node.columnList

    if (colSpec == ".'") {
      Seq(columnBase.id)

    } else {
      val cs = PathIdentifier.resolve(colSpec, columnBase.id)

      if (PathIdentifier.isGlobPattern(cs)) {
        columnList.matching(cs).map(_.id).toSeq
      } else {
        if (columnList.contains(cs)) Seq(cs) else sys.error(s"reference of unknown column $cs")
      }
    }
  }

  protected def _cellRef (colPath: String, rowPath: String): CellRef[_] = {
    val rowList = node.rowList
    val columnList = node.columnList

    val row =
      rowList.get( PathIdentifier.resolve(rowPath, rowList.id)) match {
        case Some(row) => row
        case None => sys.error(s"reference of unknown row: $rowPath")
      }

    val col = if (colPath == ".") {
      columnList(node.id)
    } else {
      columnList.get( PathIdentifier.resolve(colPath, columnBase.id)) match {
        case Some(col) => col
        case None => sys.error(s"reference of unknown column $colPath")
      }
    }

    row.cellRef(col.id)
  }
}

object ConstraintFormulaList {

  val _level_     = asc("level")
  val _mark_      = asc("mark")
  val _constraints_ = asc("constraints")

  val defaultLevel = 2

  // this uses the same syntax as CellFormulaList specs but with different meaning - the base-column-spec can be a pattern that
  // matches multiple columns, which causes replication of the formula for all matching columns. This enables a single definition
  // that applies to each matching column.

  // constraint formulas are not 1:1 associated with cells and hence do not have a mandatory single row id. Constraint formulas do have
  // an id simple for identification. However, each formula spec can have a optional 'mark' spec that contains a glob pattern identifying
  // cells (potentially across columns) that are associated with this formula. This does not necessarily have to be the set of dependencies

  type ConstraintFormulaSpec = (String,String,Int,Option[String],Option[String])   // formula spec: (info, expr, level, [time-trigger], [mark-cells])
  type FormulaSpec = (String, Seq[(String,ConstraintFormulaSpec)])  // (base-column-spec, {(formula-id, formula-spec)})
}
import ConstraintFormulaList._


/**
  * a list of value and time triggered ConstraintFormulas that represent data model properties
  */
class ConstraintFormulaList (val id: String, val info: String, val date: DateTime, val formulaSpecs: Seq[FormulaSpec]) extends FormulaList {

  protected var valueTriggeredFormulas = Seq.empty[ConstraintFormula]
  protected var timeTriggeredFormulas = Seq.empty[ConstraintFormula]

  def compileWith(node: Node, funcLib: CellFunctionLibrary): Result = {
    var vFormulas = ArrayBuffer.empty[ConstraintFormula]
    val tFormulas = ArrayBuffer.empty[ConstraintFormula]

    formulaSpecs.foreach { e =>
      val colPattern = PathIdentifier.resolve(e._1,node.id)
      val specs = e._2

      node.columnList.matching(colPattern).foreach { baseCol=>
        specs.foreach { fs=>
          val (fidBase,(finfo,src,level,timeTrigger,mark)) = fs
          val fid = s"${baseCol.id}::$fidBase"

          ConstraintFormula.compile(baseCol,fid,finfo,src,level,mark,node,funcLib) match {
            case SuccessValue(cf) =>
              ifSome(timeTrigger){ cf.setTimer }
              if (cf.hasTimeTrigger) tFormulas += cf else vFormulas += cf

            case e@Failure(msg) =>
              return e // short circuit
          }
        }
      }
    }

    if (vFormulas.nonEmpty) valueTriggeredFormulas = vFormulas.toSeq
    if (tFormulas.nonEmpty) timeTriggeredFormulas = tFormulas.toSeq

    Success
  }

  def evaluateValueTriggeredFormulas (ctx: EvalContext)(action: (ConstraintFormula,Boolean)=>Unit): Unit = {
    valueTriggeredFormulas.foreach { cf=>
      if (cf.canEvaluateIn(ctx) && cf.hasUpdatedDependencies(ctx)) action(cf,cf.eval(ctx))
    }
  }

  def evaluateTimeTriggeredFormulas (ctx: EvalContext)(action: (ConstraintFormula,Boolean)=>Unit): Unit = {
    timeTriggeredFormulas.foreach { cf=>
      if (cf.canEvaluateIn(ctx) && cf.isTriggeredAt(ctx.evalDate)) action(cf,cf.eval(ctx))
    }
  }


  //--- debugging

  def printValueTriggeredFormulaExprs(): Unit = valueTriggeredFormulas.foreach { f=> println(s"formula ${f.id} := ${f.expr}") }
  def printTimeTriggeredFormulaExprs(): Unit = timeTriggeredFormulas.foreach { f=> println(s"formula ${f.id} := ${f.expr}") }

}

class ConstraintFormulaListParser extends FormulaListParser[ConstraintFormulaList] {

  /**
    * this only parses the specs and does not yet compile the formula exprs, hence it does not need to know
    * the node (CL, RL) yet
    */
  def parse (buf: Array[Byte]): Option[ConstraintFormulaList] = {
    initialize(buf)

    try {
      readNextObject {
        val id = readQuotedMember(_id_).toString  // for the formula list
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        val formulaSpecs = readNextObjectMemberInto(_constraints_, ArrayBuffer.empty[FormulaSpec]) {
          val colSpec = readObjectMemberName().toString  // can be a pattern (expanded during compilation)
          val formulas = readCurrentObjectInto(ArrayBuffer.empty[(String,ConstraintFormulaSpec)]) {
            val formulaId = readObjectMemberName().toString // can be a pattern (expanded during compilation)
            val formulaInfo = readQuotedMember(_info_).toString
            val formulaSrc = readQuotedMember(_src_).toString
            val level = readUnQuotedMember(_level_).toInt
            val triggerSrc = readOptionalQuotedMember(_trigger_).map(_.toString)
            val mark = readOptionalQuotedMember(_mark_).map(_.toString)

            skipPastAggregate()
            (formulaId,(formulaInfo,formulaSrc,level,triggerSrc,mark))
          }.toSeq
          (colSpec,formulas)
        }.toSeq

        Some( new ConstraintFormulaList(id,info,date, formulaSpecs))
      }
    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed constraint formula list: ${x.getMessage}")
        None
    }
  }
}