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

import gov.nasa.race.share.Row.{integerListRow, integerRow, realRow}
import gov.nasa.race.{Failure, SuccessValue}
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpec

import scala.reflect.{ClassTag, classTag}

/**
  * reg test for FieldExpression parsing and evaluation
  */
class CellExpressionSpec extends AnyFlatSpec with RaceSpec {

  //--- test data

  val date = DateTime.parseYMDT("2020-06-28T12:00:00.000")
  val validChangeDate = DateTime.parseYMDT("2020-09-10T16:30:00")

  val nodeList = NodeList("/nodes", date,
    self = NodeInfo("/nodes/n1")
  )

  val C1 = "/columns/c1"
  val C2 = "/columns/c2"
  val C3 = "/columns/c3"

  val columnList = ColumnList("/columns", date,
    Column(C1),
    Column(C2),
    Column(C3)
  )

  val R1 = "/rows/r1"
  val R2 = "/rows/r2"
  val R3 = "/rows/r3"
  val R4 = "/rows/r4"
  val R5 = "/rows/r5"

  val rowList = RowList("/rows", date,
    integerRow(R1),
    realRow(R2),
    realRow(R3),
    integerRow(R4),
    integerListRow(R5)
  )

  val cds = ColumnData.cdMap(
    ColumnData( C1,date,
      R1 -> IntegerCellValue(42,date),
      R2 -> RealCellValue(0.42,date),
      // we leave r3 undefined
      R4 -> IntegerCellValue(43,date),
      R5 -> IntegerListCellValue(IntegerList(43,41), date)
    ),
    ColumnData( C2,date,
      R1 -> IntegerCellValue(43,date),
      R2 -> RealCellValue(0.43,date)
    )
  )

  val node = Node( nodeList,columnList,rowList,cds)

  val funcLib = new CellFunctionLibrary


  println("#-- data:")
  println("\n  rows:")
  rowList.foreach(row => println(s"  $row"))
  println("\n  values:")
  cds("/columns/c1").foreachOrdered(rowList) { (id,cv) => println(s"  $id = $cv") }

  //--- aux funcs

  def compile [T :ClassTag](parser: CellExpressionParser, formula: String): T = {
    try {
      parser.compile(formula) match {
        case SuccessValue(ce) =>
          if (classTag[T].runtimeClass.isAssignableFrom(ce.getClass)) {
            println("success: " + ce)
            ce.asInstanceOf[T]
          } else {
            fail(s"wrong CellExpression type: ${ce.getClass}, expecting: ${classTag[T].runtimeClass}")
          }
        case Failure(msg) =>
          fail(s"compilation failed with $msg")
      }

    } catch {
      case x: Throwable =>
        x.printStackTrace()
        println("compile failed with " + x)
        fail(x.getMessage)
    }
  }

  def evalFor [T](ctx: EvalContext, expr: CellExpression[T]): T = {
    val res = expr.eval(ctx)
    println(s" -->  $res")
    res
  }

  //--- the tests

  "a CellExpressionParser" should "parse a simple function formula into a CellExpression that can be evaluated" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R3),funcLib)
    val formula = "(RealSum ../r1 ../r2)"  // r1 is integer

    println(s"\n#-- function with explicit column-local cell references: '$formula'")

    val ctx = new BasicEvalContext( node, validChangeDate)

    val expr = compile[RealExpression](p, formula)
    val v: Double = evalFor(ctx, expr)
    assert(v == 42.42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // r1, r2
  }

  "a CellExpressionParser" should "parse a function formula with cell patterns" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R3),funcLib)
    val formula = "(RealSum ../r{1,2})"

    println(s"\n#-- function with cell pattern: '$formula'")

    val ctx = new BasicEvalContext( node, validChangeDate)

    val expr = compile[RealExpression](p, formula)
    val v: Double = evalFor(ctx, expr)
    assert(v == 42.42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // r1, r2
  }

  "a CellExpressionParser" should "parse a function formula with column reference patterns" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R1),funcLib)
    val formula = "(IntAvgReal ../c{1,2}::.)"

    println(s"\n#-- function with column pattern: '$formula' for ${rowList(R1).getClass}")

    val ctx = new BasicEvalContext( node, validChangeDate)

    val expr = compile[RealExpression](p, formula)
    val v: Double = evalFor(ctx,expr)
    assert(v == 42.5)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // c1::r1, c2::r1
  }

  "a CellExpressionParser" should "parse a nested formula" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R3),funcLib)
    val formula = "(RealSum ../r2 (IntMax ../r{1,4}) -0.42)"

    println(s"\n#-- function with nested expression args: '$formula'")

    val ctx = new BasicEvalContext( node, validChangeDate)

    val expr = compile[RealExpression](p, formula)
    val v: Double = evalFor(ctx, expr)
    assert(v == 43.0)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 3) // r1,r2,r4
  }

  "a CellExpressionParser" should "parse a heterogeneous cell func" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R1),funcLib)
    val formula = "(IntCellInc . 1)"

    println(s"\n#-- function with cell-reference: '$formula'")

    val ctx = new BasicEvalContext( node, validChangeDate)

    val expr = compile[IntegerExpression](p, formula)
    val v = evalFor(ctx, expr)
    assert(v == 43)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 1) // r1
  }

  "a CellExpressionParser" should "parse array push funcs with . paths" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R5),funcLib)
    val formula = "(IntListCellPushN . ../r4 2)"

    println(s"\n#-- array pushn function: '$formula'")

    val expr = compile[IntegerListExpression](p, formula)
    val ctx = new BasicEvalContext( node, validChangeDate)

    val v: IntegerList = evalFor(ctx,expr)
    assert(v(0) == 43 && v(1) == 43 && v.length == 2)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // r4
  }

  "a CellExpressionParser" should "parse array avg func" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R4),funcLib)
    val formula = "(IntListCellAvgInt ../r5)"

    println(s"\n#-- array avg function: '$formula'")

    val expr = compile[IntegerExpression](p, formula)
    val ctx = new BasicEvalContext( node, validChangeDate)

    val v = evalFor(ctx,expr)
    assert(v == 42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 1) // r5
  }

  "a CellExpressionParser" should "detect arity errors" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R5),funcLib)
    val formula = "(IntListCellPushN ../r5 3)"

    println(s"\n#--- invalid function arguments (arity) for formula: '$formula'")

    try {
      p.compile(formula) match {
        case SuccessValue(ce) =>
          fail(s"compiler failed to detect error and produced expr: $ce")
        case Failure(msg) =>
          println(s"  Ok, compiler detected error: '$msg")
      }
    } catch {
      case x: Throwable =>
        x.printStackTrace()
        println("compile failed with " + x)
        fail(x.getMessage)
    }
  }

  "a CellExpressionParser" should "detect argument type errors" in {
    val p = new CellFormulaParser(node, columnList(C1), rowList(R5),funcLib)
    val formula = "(IntListCellPushN ../r4 ../r5 3)"

    println(s"\n#--- invalid function arguments (type) for formula: '$formula'")

    try {
      p.compile(formula) match {
        case SuccessValue(ce) =>
          fail(s"compiler failed to detect error and produced expr: $ce")
        case Failure(msg) =>
          println(s"  Ok, compiler detected error: '$msg")
      }
    } catch {
      case x: Throwable =>
        x.printStackTrace()
        println("compile failed with " + x)
        fail(x.getMessage)
    }
  }
}
