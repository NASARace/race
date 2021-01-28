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
import gov.nasa.race.{Failure, Result, ResultValue, Success, SuccessValue, ifSome}

import scala.collection.mutable.ArrayBuffer

object ConstraintFormula {
  def compile (id: String, info: String, src: String, node: Node, funLib: CellFunctionLibrary): ResultValue[ConstraintFormula] = {
    val parser = new ConstraintFormulaParser(node, funLib)
    parser.compile(src).flatMap { expr =>
      if (expr.isInstanceOf[BoolExpression]) SuccessValue( new ConstraintFormula(id,info,src,expr.asInstanceOf[BoolExpression]))
      else Failure("constraint not a boolean cell formula")
    }
  }
}

/**
  * a CellFormula that is used to check cell value properties
  */
case class ConstraintFormula (id: String, info: String, src: String,  expr: BoolExpression) extends CellFormula[Boolean] {
  def eval (ctx: EvalContext): Boolean = expr.eval(ctx)
}

/**
  * a specialized CellExpressionParser for expressions that are not compiled in the context of a given column or row
  */
class ConstraintFormulaParser (node: Node, funLib: CellFunctionLibrary) extends CellExpressionParser(funLib) {

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
      Seq(node.id)

    } else {
      val cs = PathIdentifier.resolve(colSpec, columnList.id)
      if (PathIdentifier.isGlobPattern(colSpec)) {
        columnList.matching(colSpec).map(_.id).toSeq
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
      columnList.get( PathIdentifier.resolve(colPath, columnList.id)) match {
        case Some(col) => col
        case None => sys.error(s"reference of unknown column $colPath")
      }
    }

    row.cellRef(col.id)
  }
}


/**
  * a list of value and time triggered ConstraintFormulas that represent data model properties
  */
class ConstraintFormulaList (val id: String, val info: String, val date: DateTime, val formulaSpecs: Seq[ConstraintFormulaSpec]) extends FormulaList {

  protected var valueTriggeredFormulas = Seq.empty[ConstraintFormula]
  protected var timeTriggeredFormulas = Seq.empty[ConstraintFormula]

  def compileWith(node: Node, funcLib: CellFunctionLibrary): Result = {
    var vFormulas = ArrayBuffer.empty[ConstraintFormula]
    val tFormulas = ArrayBuffer.empty[ConstraintFormula]

    formulaSpecs.foreach { fs =>
      val cfId = fs._1
      val cfInfo = fs._2
      val (exprSrc, timerSpec) = fs._3

      ConstraintFormula.compile(cfId,cfInfo,exprSrc,node,funcLib) match {
        case SuccessValue(cf) =>
          ifSome(timerSpec){ cf.setTimer }
          if (cf.hasTimeTrigger) tFormulas += cf else vFormulas += cf

        case e@Failure(msg) => e // short circuit
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
        val id = readQuotedMember(_id_).toString  // list id
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        val formulaSpecs = readNextArrayMemberInto(_constraints_, ArrayBuffer.empty[ConstraintFormulaSpec]) {
          readNextObject[ConstraintFormulaSpec] {
            val formulaId = readQuotedMember(_id_).toString
            val formulaInfo = readQuotedMember(_info_).toString
            val exprSrc = readQuotedMember(_src_).toString
            val triggerSrc = readOptionalQuotedMember(_trigger_).map(_.toString)

            (formulaId, formulaInfo, (exprSrc, triggerSrc))
          }

        }.toSeq

        Some(new ConstraintFormulaList(id, info, date, formulaSpecs))
      }
    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed constraint list: ${x.getMessage}")
        None
    }
  }
}