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

import gov.nasa.race.common.{JsonParseException, JsonWriter, PathIdentifier}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.{Failure, Result, ResultValue, Success, SuccessValue, ifSome}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object ConstraintFormula extends JsonConstants {
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

  val LEVEL       = asc("level")
  val SRC         = asc("src")
  val TRIGGER     = asc("trigger")
  val ASSOC       = asc("assoc")
  val COL         = asc("col")
  val ROW         = asc("row")
}

/**
  * a CellFormula that is used to check cell value properties
  *
  * if a formula evaluates to false we report the property as violated
  */
case class ConstraintFormula (id: String, info: String, src: String,  expr: BoolExpression, level: Int, assoc: Set[CellRef[_]]) extends CellFormula[Boolean] {
  import ConstraintFormula._
  def eval (ctx: EvalContext): Boolean = expr.eval(ctx)

  def serializeAsMemberObjectTo (w: JsonWriter): Unit = {
    w.writeObjectMember(id) { w=>
      w.writeStringMember(ID,id)
      w.writeStringMember(INFO, info)
      w.writeStringMember(SRC,src)
      w.writeIntMember(LEVEL, level)

      w.writeArrayMember(ASSOC) { w=>
        assoc.foreach { cr =>
          w.writeObject { w =>
            w.writeStringMember(COL, cr.colId)
            w.writeStringMember(ROW, cr.rowId)
          }
        }
      }
    }
  }
}

/**
  * a specialized CellExpressionParser for expressions that are not compiled in the context of a given column or row
  */
class ConstraintFormulaParser (node: Node, columnBase: Column, funLib: CellFunctionLibrary) extends CellExpressionParser(funLib) {

  protected def _matchRows (rowSpec: String): Seq[String] = {
    val rowList = node.rowList

    if (PathIdentifier.isGlobPattern(rowSpec)) {
      rowList.matching(rowSpec).map(_.id).toSeq
    } else {
      if (rowList.contains(rowSpec)) Seq(rowSpec) else sys.error(s"reference of unknown row $rowSpec")
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
      columnList(columnBase.id)
    } else {
      columnList.get( PathIdentifier.resolve(colPath, columnBase.id)) match {
        case Some(col) => col
        case None => sys.error(s"reference of unknown column $colPath")
      }
    }

    row.cellRef(col.id)
  }
}

object ConstraintFormulaList extends JsonConstants {
  val defaultLevel = 2

  // this uses the same syntax as CellFormulaList specs but with different meaning - the base-column-spec can be a pattern that
  // matches multiple columns, which causes replication of the formula for all matching columns. This enables a single definition
  // that applies to each matching column.

  // constraint formulas are not 1:1 associated with cells and hence do not have a mandatory single row id. Constraint formulas do have
  // an id simple for identification. However, each formula spec can have a optional 'mark' spec that contains a glob pattern identifying
  // cells (potentially across columns) that are associated with this formula. This does not necessarily have to be the set of dependencies

  type ConstraintFormulaSpec = (String,String,String,Int,Option[String],Option[String])   // formula spec: (id, info, src, level, [trigger], [assoc])
  type ColumnConstraintSpec = (String, Seq[ConstraintFormulaSpec])  // (column-spec, {formula-spec})
}
import ConstraintFormulaList._


/**
  * a list of value and time triggered ConstraintFormulas that represent data model properties
  */
class ConstraintFormulaList (val id: String, val info: String, val date: DateTime, val formulaSpecs: Seq[ColumnConstraintSpec]) extends FormulaList {

  protected var valueTriggeredFormulas = Seq.empty[ConstraintFormula]
  protected var timeTriggeredFormulas = Seq.empty[ConstraintFormula]

  def compileWith(node: Node, funcLib: CellFunctionLibrary): Result = {
    var vFormulas = ArrayBuffer.empty[ConstraintFormula]
    val tFormulas = ArrayBuffer.empty[ConstraintFormula]

    formulaSpecs.foreach { e =>
      val colPattern = e._1
      val specs = e._2

      node.columnList.matching(colPattern).foreach { baseCol=>
        specs.foreach { fs=>
          val (fidBase,finfo,src,level,timeTrigger,mark) = fs
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

  def evaluateValueTriggeredFormulas (ctx: EvalContext)(action: (ConstraintFormula,DateTime,Boolean)=>Unit): Unit = {
    valueTriggeredFormulas.foreach { cf=>
      if (cf.canEvaluateIn(ctx) && cf.hasUpdatedDependencies(ctx)) action(cf, ctx.evalDate, cf.eval(ctx))
    }
  }

  def evaluateTimeTriggeredFormulas (ctx: EvalContext)(action: (ConstraintFormula,DateTime,Boolean)=>Unit): Unit = {
    timeTriggeredFormulas.foreach { cf=>
      if (cf.canEvaluateIn(ctx) && cf.isTriggeredAt(ctx.evalDate)) action(cf, ctx.evalDate, cf.eval(ctx))
    }
  }


  //--- debugging

  def printValueTriggeredFormulaExprs(): Unit = valueTriggeredFormulas.foreach { f=>
    println(s"formula ${f.id} := ${f.expr}")
    println(s"""   dependencies: ${f.dependencies.mkString(",")}""")
  }
  def printTimeTriggeredFormulaExprs(): Unit = timeTriggeredFormulas.foreach { f=> println(s"formula ${f.id} := ${f.expr}") }

}

class ConstraintFormulaListParser extends FormulaListParser[ConstraintFormulaList] {
  import FormulaList._

  def readConstraintFormulaSpecs(): Seq[ConstraintFormulaSpec] = {
    val specs = mutable.Buffer.empty[ConstraintFormulaSpec]

    foreachElementInCurrentArray {
      var id: String = null
      var info: String = ""
      var src: String = null
      var level: Int = defaultLevel
      var trigger: Option[String] = None
      var assoc: Option[String] = None

      foreachMemberInCurrentObject {
        case ID => id = quotedValue.intern
        case INFO => info = quotedValue.toString
        case SRC => src = quotedValue.toString
        case ConstraintFormula.LEVEL => level = unQuotedValue.toInt
        case ConstraintFormula.TRIGGER => trigger = Some(quotedValue.toString)
        case ConstraintFormula.ASSOC => assoc = Some(quotedValue.toString)
      }

      if (id != null && src != null){
        val e = (id,info,src,level,trigger,assoc)
        specs += e
      }
    }

    specs.toSeq
  }

  def readColumnConstraintSpecs(): Seq[ColumnConstraintSpec] = {
    val specs = mutable.Buffer.empty[ColumnConstraintSpec]

    foreachElementInCurrentArray {
      val colSpec = readCurrentObject {
        var colId: String = null
        var formulas = Seq.empty[ConstraintFormulaSpec]

        foreachMemberInCurrentObject {
          case COL_ID => colId = quotedValue.toString
          case FORMULAS => formulas = readCurrentArray( readConstraintFormulaSpecs() )
        }
        (colId -> formulas)
      }
      specs += colSpec
    }
    specs.toSeq
  }

  def readConstraintFormulaList(): ConstraintFormulaList = {
    readCurrentObject {
      var id: String = null
      var info: String = ""
      var date: DateTime = DateTime.UndefinedDateTime
      var columnSpecs = Seq.empty[ColumnConstraintSpec]

      foreachMemberInCurrentObject {
        case ID => id = quotedValue.toString
        case INFO => info = quotedValue.toString
        case DATE => date = dateTimeValue
        case COLUMNS => columnSpecs = readCurrentArray(readColumnConstraintSpecs())
      }

      if (id == null) throw exception("missing 'id' in constraintFormulaList")
      new ConstraintFormulaList(id, info, date, columnSpecs)
    }
  }

  /**
    * this only parses the specs and does not yet compile the formula exprs, hence it does not need to know
    * the node (CL, RL) yet
    */
  def parse (buf: Array[Byte]): Option[ConstraintFormulaList] = {
    initialize(buf)

    try {
      readNextObject {
        Some(readNextObjectMember(CONSTRAINT_FORMULA_LIST) {
          readConstraintFormulaList()
        })
      }
    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed constraint formula list: ${x.getMessage}")
        None
    }
  }
}