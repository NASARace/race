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

import gov.nasa.race.{Failure, Result, ResultValue, Success, SuccessValue, ifSome}
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonParseException, PathIdentifier, TimeTrigger, UTF8JsonPullParser}
import gov.nasa.race.share.FormulaList.CellValueFormulaSpec
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.SeqMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.parsing.combinator._


object CellFormula {
  def compile (src: String, compiledRow: Row[_], compiledColumn: Column,
               node: Node, funLib: CellFunctionLibrary): ResultValue[CellFormula[_]] = {
    val parser = new CellFormulaParser(node, compiledColumn, compiledRow, funLib)
    parser.compile(src).flatMap( compiledRow.formula(src,_))
  }
}

/**
  * base type for CellExpressions that are compiled from textual representations in the context of given
  * ColumnList, RowList and Column/Row instances
  *
  * note that the compiled CellExpression objects are not shared between different column/row combinations even if
  * the src is the same
  */
abstract class CellFormula[T: ClassTag] extends CellExpression[T] {
  val src: String
  val expr: CellExpression[T]

  val dependencies: Set[CellRef[_]] = expr.dependencies(Set.empty[CellRef[_]])

  /**
    * does ctx have defined values for all our dependencies
    */
  def canEvaluateIn (ctx: EvalContext): Boolean = {
    !dependencies.exists( cr=> ctx.getCellValue(cr.colId,cr.rowId).isEmpty )
  }

  /**
    * has any of our dependencies been changed in ctx
    */
  def hasUpdatedDependencies (ctx: EvalContext): Boolean = {
    ctx.existsChange { (colId,row,cv) => dependencies.contains(row.cellRef(colId)) }
  }

  def computeCellValue (ctx: EvalContext): CellValue[T] = expr.computeCellValue(ctx)

  //--- time triggered formulas  (TODO - should this be a sub-type)

  protected var timerSrc: Option[String] = None
  protected var timer: Option[TimeTrigger] = timerSrc.map(TimeTrigger.apply)

  def setTimer(src: String): Unit = {
    timerSrc = Some(src)
    timer = Some(TimeTrigger(src))
  }

  def hasTimeTrigger: Boolean = timer.isDefined

  def isTriggeredAt (d: DateTime): Boolean = {
    timer.isDefined && timer.get.check(d)
  }

  def armTimer(refDate: DateTime): Unit = {
    if (timer.isDefined) timer.get.armCheck(refDate)
  }

  def dependsOnAny (changes: Iterable[CellRef[_]]): Boolean = changes.exists( dependencies.contains)

}

case class IntegerCellFormula(src: String, expr: IntegerExpression) extends CellFormula[Long] with IntegerExpression {
  def eval (ctx: EvalContext): Long = expr.eval(ctx)
  override def computeCellValue (ctx: EvalContext): IntegerCellValue = expr.computeCellValue(ctx)
}

case class RealCellFormula (src: String, expr: RealExpression) extends CellFormula[Double] with RealExpression {
  def eval (ctx: EvalContext): Double = expr.evalToReal(ctx)
  override def computeCellValue (ctx: EvalContext): RealCellValue = expr.computeCellValue(ctx)
}

case class BoolCellFormula (src: String, expr: BoolExpression) extends CellFormula[Boolean] with BoolExpression {
  def eval (ctx: EvalContext): Boolean = expr.eval(ctx)
  override def computeCellValue (ctx: EvalContext): BoolCellValue = expr.computeCellValue(ctx)
}

case class StringCellFormula (src: String, expr: StringExpression) extends CellFormula[String] with StringExpression {
  def eval (ctx: EvalContext): String = expr.eval(ctx)
  override def computeCellValue (ctx: EvalContext): StringCellValue = expr.computeCellValue(ctx)
}

case class LinkCellFormula (src: String, expr: LinkExpression) extends CellFormula[String] with LinkExpression {
  def eval (ctx: EvalContext): String = expr.eval(ctx)
  override def computeCellValue (ctx: EvalContext): LinkCellValue = expr.computeCellValue(ctx)
}

case class IntegerListCellFormula (src: String, expr: IntegerListExpression) extends CellFormula[IntegerList] with IntegerListExpression {
  def eval (ctx: EvalContext): IntegerList = expr.eval(ctx)
  override def computeCellValue (ctx: EvalContext): IntegerListCellValue = expr.computeCellValue(ctx)
}

case class RealListCellFormula (src: String, expr: RealListExpression) extends CellFormula[RealList] with RealListExpression {
  def eval (ctx: EvalContext): RealList = expr.evalToRealList(ctx)
  override def computeCellValue (ctx: EvalContext): RealListCellValue = expr.computeCellValue(ctx)
}

//----------------- combinator parser for s-expr cell expression sources

/**
  * a combinator parser for CellExpressions specified as s-exprs
  *
  * examples:
  *     (IntSum /a/\*)
  *     (IntListPushN . /a/x 2)
  *
  * CellRefs can be specified as glob patterns ('*' is only escaped because of scaladoc and can be used directly),
  * which are expanded into matching Seq[CellRef]
  *
  * '.' refers to the column / row id this formula is compiled for
  */
abstract class CellExpressionParser (funLib: CellFunctionLibrary) extends DebugRegexParsers {

  // we need types we can match on
  class CRBuffer (initCapacity: Int = 8) extends ArrayBuffer[CellRef[_]](initCapacity)
  class CEBuffer (initCapacity: Int = 8) extends ArrayBuffer[CellExpression[_]](initCapacity)

  //--- provided by concrete class
  protected def _matchRows (rowSpec: String): Seq[String]
  protected def _matchColumns (colSpec: String): Seq[String]
  protected def _cellRef (colPath: String, rowPath: String): CellRef[_]

  //--- tokens
  def integer: Parser[IntegerCellValueConst]    = """-?\d+""".r ^^ { s=> IntegerCellValueConst(s.toLong) }
  def real: Parser[RealCellValueConst] = """-?\d+\.\d+""".r ^^ { s=> RealCellValueConst(s.toDouble) }
  def bool: Parser[BoolCellValueConst] = "true|false".r ^^ { s=> BoolCellValueConst(s.toBoolean) }
  def string: Parser[StringCellValueConst] = """\".*\"""".r ^^ { s=> StringCellValueConst(s.substring(1,s.length-2)) }

  def funId: Parser[String] = """[A-Z][0-9A-Za-z_]*""".r ^^ { _.intern }
  def pathSpec: Parser[String] = """[a-zA-Z0-9_/.*?!{},\[\]]+""".r
  def path: Parser[String] = """[a-zA-Z/][a-zA-Z/_0-9]*""".r ^^ { _.intern }

  def const: Parser[CellValueConst[_]] = real | integer | bool | string

  // a single explicit CellRef
  def cellRef: Parser[CellRef[_]]     = path ~ opt( "::" ~> path) ^^ {
    case pRow ~ None => _cellRef(".", pRow)
    case pCol ~ Some(pRow) => _cellRef(pCol, pRow)
  }

  // a (potential) sequence of CellRefs which can be produced from patterns
  // note this has to be type checked in the caller context
  def cellRefSpec: Parser[CRBuffer] = {
    pathSpec ~ opt( "::" ~> pathSpec) ^^ {
      case rowSpec ~ None =>
        val paths = _matchRows(rowSpec)
        paths.foldLeft(new CRBuffer(paths.length))( (acc,p) => {
          acc += _cellRef(".",p)
        })
      case colSpec ~ Some(rowSpec) =>
        val cols = _matchColumns(colSpec)
        val rows = _matchRows(rowSpec)

        val paths = new CRBuffer(cols.size * rows.size)
        cols.foreach(c => rows.foreach { r =>
          paths += _cellRef(c,r)
        })
        paths
    }
  }

  def cellRefs: Parser[Set[CellRef[_]]] = {
    repsep( cellRefSpec, ",").map { crs=>
      crs.foldLeft( Set.empty[CellRef[_]])( (acc,crb)=> acc ++ crb )
    }
  }

  def funArgs: Parser[CEBuffer] = {
    val argBuf = new CEBuffer
    rep( const | cellRefSpec | funCall ).map { args =>
      args.foreach {
        case cfCall: CellFunctionCall[_] => argBuf += cfCall
        case cvConst: CellValueConst[_] => argBuf += cvConst
        case crs: CRBuffer => argBuf ++= crs
      }
      argBuf
    }
  }

  def funCall: Parser[CellFunctionCall[_]] = {
    ("(" ~> funId ~ funArgs <~ ")") >> {
      case id ~ args =>
        funLib.get(id) match {
          case Some(fun) =>
            fun.genCall(args.toSeq) match {
              case Right(cfCall) => success(cfCall)
              case Left(errMsg) => err(errMsg)
            }
          case None => err(s"unknown function $id")
        }
    }
  }

  // no cell patterns or lists - we need a single CellExpression
  def expr: Parser[CellExpression[_]] = funCall | const | cellRef

  /**
    * this is the public entry - note this does not yet check or adapt type compatibility
    */
  def compile (src: String): ResultValue[CellExpression[_]] = {
    parseAll(expr, src) match {
        // Success, Failure and Error are unfortunately path dependent types we cannot rename
      case this.Success(ce: CellExpression[_],_) => SuccessValue(ce)
      case this.Failure(msg,_) => gov.nasa.race.Failure(s"formula '$src' failed to compile: $msg")
      case this.Error(msg,_) => gov.nasa.race.Failure(s"error compiling formula '$src': $msg")
      case other => throw sys.error(s"parse result $other can't happen") // bogus compiler warning ?
    }
  }

  def parseCellRefs (src: String): ResultValue[Set[CellRef[_]]] = {
    parseAll(cellRefs,src) match {
      case this.Success(crs: Set[CellRef[_]],_) => SuccessValue(crs)
      case this.Failure(msg,_) => gov.nasa.race.Failure(s"cellRef list '$src' failed to compile: $msg")
      case this.Error(msg,_) => gov.nasa.race.Failure(s"error compiling cellRef list '$src': $msg")
      case other => throw sys.error(s"parse result $other can't happen") // bogus compiler warning ?
    }
  }
}

/**
  * combinator parser for cell expressions to compute cell values.
  * here we always have the context of column and row this formula applies to
  */
class CellFormulaParser(node: Node, compiledColumn: Column, compiledRow: Row[_], funLib: CellFunctionLibrary) extends CellExpressionParser(funLib) {

  protected def _matchRows (rowSpec: String): Seq[String] = {
    val rowList = node.rowList

    if (rowSpec == ".") {
      Seq(compiledRow.id)
    } else {
      val rs = PathIdentifier.resolve(rowSpec,compiledRow.id)
      if (PathIdentifier.isGlobPattern(rs)) {
        rowList.matching(rs).map(_.id).toSeq
      } else {
        if (rowList.contains(rs)) Seq(rs) else sys.error(s"reference of unknown row $rs")
      }
    }
  }

  protected def _matchColumns (colSpec: String): Seq[String] = {
    val columnList = node.columnList

    if (colSpec == ".") {
      Seq(compiledColumn.id)

    } else {
      val cs = PathIdentifier.resolve(colSpec, compiledColumn.id)
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

    val row = if (rowPath == ".") {
      compiledRow
    } else {
      rowList.get( PathIdentifier.resolve(rowPath, compiledRow.id)) match {
        case Some(row) => row
        case None => sys.error(s"reference of unknown row: $rowPath")
      }
    }

    val col = if (colPath == ".") {
      compiledColumn
    } else {
      columnList.get( PathIdentifier.resolve(colPath, compiledColumn.id)) match {
        case Some(col) => col
        case None => sys.error(s"reference of unknown column $colPath")
      }
    }

    row.cellRef(col.id)
  }
}

/**
  * helper to trace combinator parser rule invocation
  */
trait DebugRegexParsers extends RegexParsers {
  class Wrap[+T](name:String,parser:Parser[T]) extends Parser[T] {
    def apply(in: Input): ParseResult[T] = {
      val first = in.first
      val pos = in.pos
      val offset = in.offset
      val t = parser.apply(in)
      println(name+".apply for token "+first+
        " at position "+pos+" offset "+offset+" returns "+t)
      t
    }
  }
}

object FormulaList extends JsonConstants {
  val CV_FORMULA_LIST  = asc("cellValueFormulaList")
  val CONSTRAINT_FORMULA_LIST = asc("constraintFormulaList")
  val FORMULAS     = asc("formulas")
  val COL_ID       = asc("columnId")
  val ROW_ID       = asc("rowId")
  val SRC          = asc("src")
  val TRIGGER      = asc("trigger")

  type FormulaSrcs = (String,Option[String])   // formula sources: (expr, opt time-trigger)
  type RowFormulaSpec = (String,FormulaSrcs)  // rowId,srcs
  type CellValueFormulaSpec = (String, Seq[RowFormulaSpec])  // colId, {row,srcs}
}

trait FormulaList {
  def compileWith (node: Node,funcLib: CellFunctionLibrary): Result
}

/**
  * a named collection of (columnPattern -> (rowPattern -> cellFormula)) specs
  * that is used to compute new CellValues (either dependency value- or time-triggered)
  */
class CellValueFormulaList (val id: String, val info: String, val date: DateTime, val formulaSpecs: Seq[CellValueFormulaSpec]) extends FormulaList {

  // an ordered map of ordered maps: { col -> { row -> formula } }
  protected var valueTriggeredFormulas: SeqMap[String,SeqMap[String,CellFormula[_]]] = SeqMap.empty

  // a list of [(col,[row -> tt-formula])] entries
  protected var timeTriggeredFormulas: Seq[(Column, Seq[(Row[_],CellFormula[_])])] = Seq.empty

  def nonEmpty: Boolean = valueTriggeredFormulas.nonEmpty || timeTriggeredFormulas.nonEmpty

  def compileWith(node: Node, funcLib: CellFunctionLibrary): Result = {
    formulaSpecs.foreach { e=>
      val colSpec = e._1
      val rowFormulaSrcs = e._2
      val columnList = node.columnList
      val rowList = node.rowList

      columnList.matching(colSpec).foreach { col =>
        var tfs = Seq.empty[(Row[_],CellFormula[_])]
        var vfs = SeqMap.empty[String,CellFormula[_]]

        rowFormulaSrcs.foreach { e =>
          val rowSpec = e._1
          val (exprSrc,timerSpec) = e._2

          rowList.matching(rowSpec).foreach { row =>
            CellFormula.compile(exprSrc,row,col,node,funcLib) match {
              case SuccessValue(cf) =>
                ifSome(timerSpec){ cf.setTimer }
                if (cf.hasTimeTrigger) {
                  tfs = tfs :+ (row,cf)
                } else {
                  vfs = vfs + (row.id -> cf)
                }

              case e@Failure(err) =>
                return e // bail out - formula did not compile
            }
          }
        }

        if (tfs.nonEmpty) {
          timeTriggeredFormulas = timeTriggeredFormulas :+ (col -> tfs)
        }

        if (vfs.nonEmpty) {
          valueTriggeredFormulas = valueTriggeredFormulas + (col.id -> vfs) // ???? mutually exclusive ???
        }
      }
    }

    Success // no error
  }

  def getColumnFormulas(pCol: String): SeqMap[String,CellFormula[_]] = valueTriggeredFormulas.getOrElse(pCol,SeqMap.empty)

  // iterate column-wise in order of formulaList definition
  def foreachValueTriggeredColumn(f: (String,SeqMap[String,CellFormula[_]])=>Unit): Unit ={
    valueTriggeredFormulas.foreach { e=>
      val colId = e._1
      val formulas = e._2
      f(colId,formulas)
    }
  }

  def foreachTimeTriggeredColumn (f: (Column,Seq[(Row[_],CellFormula[_])])=>Unit): Unit ={
    timeTriggeredColumnFormulas.foreach { e=>
      val col = e._1
      val formulas = e._2
      f(col,formulas)
    }
  }

  def hasAnyTimeTrigger: Boolean = timeTriggeredFormulas.nonEmpty

  def timeTriggeredColumnFormulas: Seq[(Column, Seq[(Row[_],CellFormula[_])])] = timeTriggeredFormulas

  def evaluateValueTriggeredFormulas (ctx: EvalContext) (action: (ColId,Seq[CellPair])=>Unit): Unit = {
    valueTriggeredFormulas.foreach { e =>
      val colId = e._1
      val formulas = e._2

      val changes = formulas.foldLeft(Seq.empty[CellPair]) { (acc, re) => // we have to process in order of definition
        val rowId = re._1
        val cf = re._2

        if (cf.canEvaluateIn(ctx) && cf.hasUpdatedDependencies(ctx)) {
          val cv = cf.computeCellValue(ctx)
          acc :+ (rowId -> cv)   // TODO - not efficient if there are many time triggered changes (which is probably rare)
        } else acc
      }

      if (changes.nonEmpty) {
        action(colId, changes)
      }
    }
  }

  def evaluateTimeTriggeredFormulas (ctx: EvalContext) (action: (ColId,Seq[CellPair])=>Unit): Unit = {
    timeTriggeredColumnFormulas.foreach { e=>
      val col = e._1
      val formulas = e._2

      val changes = formulas.foldLeft(Seq.empty[CellPair]) { (acc,re)=>  // we have to process in order of definition
        val row = re._1
        val cf = re._2
        if (cf.isTriggeredAt(ctx.evalDate) && cf.canEvaluateIn(ctx)) {
          val cv = cf.computeCellValue(ctx)
          acc :+ (row.id -> cv)   // TODO - not efficient if there are many time triggered changes (which is probably rare)
        } else acc
      }

      if (changes.nonEmpty) {
        action(col.id, changes)
      }
    }
  }

  def armTriggeredAt (refDate: DateTime): Unit = {
    timeTriggeredColumnFormulas.foreach( _._2.foreach( _._2.armTimer(refDate)))
  }

  //--- debugging

  def printFormulaSpecs(): Unit = {
    println(s"CellValueFormulaList: '$id'")
    formulaSpecs.foreach { ce=>
      println(s"  column: '${ce._1}'")
      ce._2.foreach { re=>
        println(s"    cell: '${re._1}' := ${re._2}")
      }
    }
  }
}

trait FormulaListParser[T] extends UTF8JsonPullParser {
  def parse (buf: Array[Byte]): Option[T]
}

/**
  * a parser for json representations of CellFormulaList instances
  */
class CellValueFormulaListParser extends FormulaListParser[CellValueFormulaList] {
  import FormulaList._

  def readRowFormulaSpecs (): Seq[RowFormulaSpec] = {
    val specs = mutable.Buffer.empty[RowFormulaSpec]

    foreachElementInCurrentArray {
      var rowId: String = null
      var src: String = null
      var trigger: Option[String] = None

      foreachMemberInCurrentObject {
        case ROW_ID => rowId = quotedValue.intern
        case SRC => src = quotedValue.toString
        case TRIGGER => trigger = Some(quotedValue.toString)
      }

      specs += (rowId -> (src, trigger))
    }

    specs.toSeq
  }

  def readColumnSpecs(): Seq[CellValueFormulaSpec] = {
    val specs = mutable.Buffer.empty[CellValueFormulaSpec]

    foreachElementInCurrentArray {
      val spec = readCurrentObject {
        var colId: String = null
        var rowFormulaSpecs = Seq.empty[RowFormulaSpec]

        foreachMemberInCurrentObject {
          case COL_ID => colId = quotedValue.toString
          case FORMULAS => rowFormulaSpecs = readCurrentArray( readRowFormulaSpecs() )
        }
        (colId -> rowFormulaSpecs)
      }
      specs += spec
    }
    specs.toSeq
  }

  def readCellValueFormulaList(): CellValueFormulaList = {
    readCurrentObject {
      var id: String = null
      var info: String = ""
      var date: DateTime = DateTime.UndefinedDateTime
      var columnSpecs = Seq.empty[CellValueFormulaSpec]

      foreachMemberInCurrentObject {
        case ID => id = quotedValue.toString
        case INFO => info = quotedValue.toString
        case DATE => date = dateTimeValue
        case COLUMNS => columnSpecs = readCurrentArray( readColumnSpecs() )
      }

      if (id == null) throw exception("missing 'id' in cellValueFormulaList")
      new CellValueFormulaList( id,info,date, columnSpecs)
    }
  }

  /**
    * this only creates the CvFL with specs. It does not yet compile the expr sources and hence does not need to
    * know CL and RL (i.e. the Node)
    */
  def parse (buf: Array[Byte]): Option[CellValueFormulaList] = {
    initialize(buf)

    try {
      readNextObject {
        Some( readNextObjectMember(CV_FORMULA_LIST){ readCellValueFormulaList() } )
      }
    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed cell value formula list: ${x.getMessage}")
        None
    }
  }
}
