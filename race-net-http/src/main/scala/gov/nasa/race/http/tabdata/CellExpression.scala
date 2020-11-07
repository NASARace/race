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

import java.nio.file.{Path, PathMatcher}

import gov.nasa.race.common.UnixPath
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ClassTag, classTag}
import scala.sys.error
import scala.util.parsing.combinator._


/***
  examples:
     (sum `cat_A/ *`)
     (acc cat_B/field )
     (mul cat_A/x, sub(cat_B/y cat_X/z) )
     (avg `provider*`:.)

  - '.' refers to current column or row
  - relative paths get resolved with rowList/columnList ids
  - glob paths get expanded by respective rowList/columnList matches, expansion order is row then column
    in order of respective row/columnLists

  syntax:
     expr ::=  const | cellRef | fun
     fun ::= '(' funId funArgs ')'
     funArgs ::= { fun | const | cellRefSpec }
     const ::= integer | rational


 ***/

abstract class CellExpression[+T <: CellValue : ClassTag] {
  def eval (implicit ctx: EvalContext): T

  def dependencies (acc: Set[CellRef[_]] = Set.empty[CellRef[_]]): Set[CellRef[_]] // transitive set of all cell references of this expr
  def isAssignableTo (cls: Class[_]): Boolean = cls.isAssignableFrom(classTag[T].runtimeClass)
  def cellType: Class[_] = classTag[T].runtimeClass

  def typeChecked(expected: Class[_], msg: => String): this.type = {
    if (!expected.isAssignableFrom(cellType)) {
      sys.error(s"wrong $msg type (expected ${expected.getSimpleName} have ${cellType.getSimpleName}")
    }
    this
  }
}

trait NumExpr[+T <: NumCellValue] extends CellExpression[T] {
  def eval (implicit ctx: EvalContext): T
}

trait LongExpr extends NumExpr[LongCellValue]

trait DoubleExpr extends NumExpr[DoubleCellValue]

trait BooleanExpr extends CellExpression[BooleanCellValue]

trait StringExpr extends CellExpression[StringCellValue]

trait LongListExpr extends CellExpression[LongListCellValue]

/**
  * parser for s-expr like cell expressions
  * Note we can only compile these once we know the rowList and columnList so that we can enumerate and verify cellRefs
  */
class CellExpressionParser (columnList: ColumnList, rowList: RowList, funLib: CellFunctionLibrary) extends RegexParsers {

  // we need types we can match on
  class CRBuffer (initCapacity: Int = 8) extends ArrayBuffer[AnyCellRef](initCapacity)
  class CEBuffer (initCapacity: Int = 8) extends ArrayBuffer[AnyCellExpression](initCapacity)

  protected var compiledColumn: Column = null
  protected var compiledRow: AnyRow = null

  private def _matchRows (rowSpec: String): Seq[Path] = {
    if (UnixPath.isCurrent(rowSpec)) {
      Seq(compiledRow.id)

    } else {
      val rs = UnixPath.resolvePattern(compiledRow.id,rowSpec)
      //val rs = UnixPath.resolvePattern(rowList.id,rowSpec)
      if (UnixPath.isPattern(rowSpec)) {
        val pm = UnixPath.globMatcher(rs)
        rowList.rows.filter( e=> pm.matches(e._1)).keys.toSeq
      } else {
        val p = UnixPath.intern(rs)
        if (rowList.contains(p)) Seq(p) else sys.error(s"reference of unknown row $p")
      }
    }
  }

  private def _matchColumns (colSpec: String): Seq[Path] = {
    if (UnixPath.isCurrent(colSpec)) {
      Seq(compiledColumn.id)

    } else {
      val cs = UnixPath.resolvePattern(compiledColumn.id,colSpec)
      //val cs = UnixPath.resolvePattern(columnList.id,colSpec)
      if (UnixPath.isPattern(colSpec)) {
        val pm = UnixPath.globMatcher(cs)
        columnList.columns.filter(e => pm.matches(e._1)).keys.toSeq
      } else {
        val p = UnixPath.intern(cs)
        if (columnList.contains(p)) Seq(p) else sys.error(s"reference of unknown column $p")
      }
    }
  }

  private def _cellRef (colPath: Path, rowPath: Path): AnyCellRef = {
    val row = if (UnixPath.isCurrent(rowPath)) {
      compiledRow
    } else {
      rowList.get(compiledRow.resolve(rowPath)) match {
        case Some(row) => row
        case None => sys.error(s"reference of unknown row: $rowPath")
      }
    }

    val col = if (UnixPath.isCurrent(colPath)) {
      compiledColumn
    } else {
      columnList.get(compiledColumn.resolve(colPath)) match {
        case Some(col) => col
        case None => sys.error(s"reference of unknown column $colPath")
      }
    }

    row.createRef(col.id)
  }

  //--- tokens
  def integer: Parser[LongConst]    = """\d+""".r ^^ { s=> LongConst(s.toLong) }
  def rational: Parser[DoubleConst] = """-?\d*\.\d+""".r ^^ { s=> DoubleConst(s.toDouble) }
  def bool: Parser[BooleanConst] = "true|false".r ^^ { s=> BooleanConst(s.toBoolean) }

  def pathSpec: Parser[String] = """[a-zA-Z0-9_\/\.\*\?\!\{\}\,\[\]]+""".r
  def path: Parser[Path] = """[a-zA-Z\/][a-zA-Z\/_0-9]*""".r ^^ { UnixPath.intern(_) }

  def const (ct: Class[_]): Parser[ConstCellExpression[_<:CellValue]] = rational | integer | bool ^^ { _.typeChecked(ct, "const") }

  // a single explicit CellRef
  def cellRef (ct: Class[_]): Parser[AnyCellRef]     = path ~ opt( ":" ~> path) ^^ {
    case pRow ~ None => _cellRef(UnixPath.current, pRow).typeChecked(ct, s"cell reference '$pRow'")
    case pCol ~ Some(pRow) => _cellRef(pCol, pRow).typeChecked(ct, s"cell reference '$pCol:$pRow'")
  }

  // a (potential) sequence of CellRefs which can be produced from patterns
  // note this has to be type checked in the caller context
  def cellRefsSpec: Parser[CRBuffer]   = pathSpec ~ opt( "@" ~> pathSpec) ^^ {
    case rowSpec ~ None =>
      val paths = _matchRows(rowSpec)
      paths.foldLeft(new CRBuffer(paths.length))( (acc,p) => {
        acc += _cellRef(UnixPath.current,p)
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

  def fun (ct: Class[_]): Parser[AnyCellFunction] = {
    var fFactory: CellFunctionFactory = null
    val argBuf = new CEBuffer

    def funId: Parser[Any] = """[a-z][0-9A-Za-z_]*""".r ^^ { fName =>
      funLib.get(fName) match {
        case Some(factory) =>
          if (!ct.isAssignableFrom(factory.returnType)) {
            sys.error(s"wrong return type of function '$fName' (expected ${ct.getSimpleName} found ${factory.returnType.getSimpleName}")
          }
          fFactory = factory
        case None => sys.error(s"unknown function $fName")
      }
    }

    def funArg: Parser[Any] = {
      var at = fFactory.argTypeAt(argBuf.size)

      (fun(at) | const(at) | cellRefsSpec) ^^ {
        case cellFunc: AnyCellFunction => argBuf += cellFunc
        case cellConst: AnyCellExpression => argBuf += cellConst
        case refList: CRBuffer =>
          refList.foreach { cr=>
            argBuf += cr.typeChecked(at, s"cell ref $cr (${fFactory.id} #${argBuf.size})")
            at = fFactory.argTypeAt(argBuf.size)
          }
      }
    }

    "(" ~> funId ~ rep( funArg ) <~ ")" ^^^ {
      fFactory(argBuf.toSeq)
    }
  }

  // no cell patterns or lists - we need a single CellExpression
  def expr: Parser[AnyCellExpression] = {
    val ct = compiledRow.cellType

    fun(ct) | const(ct) | cellRef(ct)
  }

  /**
    * this is the public entry
    */
  def compile [T <: CellValue](col: Column, row: Row[T], src: String): CellExpression[T] = {
    compiledRow = row
    compiledColumn = col

    parseAll(expr, src) match {
      case Success(ce: AnyCellExpression,_) =>
        if (row.cellType == ce.cellType) {
          ce.asInstanceOf[CellExpression[T]]
        } else {
          sys.error(s"formula has wrong expression type (expected ${row.cellType.getSimpleName} found ${ce.cellType.getSimpleName})")
        }
      case Failure(msg,_) => sys.error(s"formula '$src' failed to compile: $msg")
      case Error(msg,_) => sys.error(s"error compiling formula '$src': $msg")
      case other => sys.error(s"unexpected parse result: $other")
    }
  }
}


//--- the AST elements


/**
  * cell reference (key pair to retrieve single cell from EvalContext)
  */
abstract class CellRef[+T <: CellValue : ClassTag] extends CellExpression[T] {
  def col: Path
  def row: Path

  def eval (implicit ctx: EvalContext): T = ctx.cell(col,row).asInstanceOf[T]
  override def toString: String = s"$col @ $row"

  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = acc + this
}

case class LongCellRef (col: Path, row: Path) extends CellRef[LongCellValue] with LongExpr

case class DoubleCellRef (col: Path, row: Path) extends CellRef[DoubleCellValue] with DoubleExpr

case class BooleanCellRef (col: Path, row: Path) extends CellRef[BooleanCellValue] with BooleanExpr

case class StringCellRef (col: Path, row: Path) extends CellRef[StringCellValue] with StringExpr

case class LongListCellRef (col: Path, row: Path) extends CellRef[LongListCellValue] with LongListExpr

/**
  * constant expression
  */
trait ConstCellExpression [+T <: CellValue] extends CellExpression[T] {
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = acc // consts don't add any dependencies
}

case class LongConst (value: Long) extends ConstCellExpression[LongCellValue] with LongExpr {
  def eval (implicit ctx: EvalContext): LongCellValue = LongCellValue(value)(DateTime.UndefinedDateTime)
  override def toString: String = value.toString
}

case class DoubleConst (value: Double) extends ConstCellExpression[DoubleCellValue] with DoubleExpr {
  def eval (implicit ctx: EvalContext): DoubleCellValue = DoubleCellValue(value)(DateTime.UndefinedDateTime)
  override def toString: String = value.toString
}

// this should be True/False objects but those are probably not used much anyways
case class BooleanConst (value: Boolean) extends ConstCellExpression[BooleanCellValue] with BooleanExpr {
  def eval (implicit ctx: EvalContext): BooleanCellValue = BooleanCellValue(value)(DateTime.UndefinedDateTime)
  override def toString: String = value.toString
}

case class LongListConst (value: Array[Long]) extends ConstCellExpression[LongListCellValue] with LongListExpr {
  def eval (implicit ctx: EvalContext): LongListCellValue = LongListCellValue(value)(DateTime.UndefinedDateTime)
  override def toString: String = value.mkString(",")
}

/**
  * a function that computes Cells from a list of CellExpressions
  */
trait CellFunction[+T <: CellValue] extends CellExpression[T] {
  val id: String = getClass.getSimpleName

  // transitively collect all dependencies of all args
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]]

  def argExprs: Seq[AnyCellExpression]

  override def toString: String = {
    s"""($id ${argExprs.mkString(" ")})"""
  }
}

trait NumFunc [+T <: NumCellValue] extends NumExpr[T] with CellFunction[T]

trait LongFunc extends NumFunc[LongCellValue] with LongExpr

trait DoubleFunc extends NumFunc[DoubleCellValue] with DoubleExpr

trait BooleanFunc extends CellFunction[BooleanCellValue] with BooleanExpr

trait LongListFunc extends CellFunction[LongListCellValue] with LongListExpr