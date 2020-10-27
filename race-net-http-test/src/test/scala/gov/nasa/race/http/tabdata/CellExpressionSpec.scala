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

import gov.nasa.race.common.TimeTrigger
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import gov.nasa.race.common.UnixPath.PathHelper
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.immutable.ListMap
import scala.collection.mutable

/**
  * reg test for FieldExpression parsing and evaluation
  */
class CellExpressionSpec extends AnyFlatSpec with RaceSpec {

  //--- test data

  implicit val date = DateTime.now

  val clid = "/columns"
  val c1 = new Column(p"$clid/c1")
  val c2 = new Column(p"$clid/c2")
  val c3 = new Column(p"$clid/c3")

  val columnList = ColumnList(
    p"$clid",
    "sample column list",
    DateTime.parseYMDT("2020-06-28T12:00:00.000"),
    Seq(c1,c2,c3).foldLeft(ListMap.empty[Path,Column])( (acc,c) => acc + (c.id -> c))
  )

  val rlid = "/rows"
  val r1 = LongRow(p"$rlid/r1", "this is editable field 1")
  val r2 = DoubleRow(p"$rlid/r2", "this is editable field 2")
  val r3 = DoubleRow(p"$rlid/r3", "this is computed field 3")
  val r4 = LongRow(p"$rlid/r4", "this is editable field 4")
  val r5 = LongListRow(p"$rlid/r5", "this is auto field 5")


  val rowList = RowList(
    p"$rlid",
    "sample data set",
    DateTime.parseYMDT("2020-06-28T12:00:00.000"),
    Seq(r1,r2,r3,r4,r5).foldLeft(ListMap.empty[Path,AnyRow])((acc, r) => acc + (r.id -> r))
  )

  val cellValues: ListMap[Path,CellValue] = ListMap(
    p"$rlid/r1" -> LongCellValue(42),
    p"$rlid/r2" -> DoubleCellValue(0.42),
    // we leave f3 undefined
    p"$rlid/r4" -> LongCellValue(43),
    p"$rlid/r5" -> LongListCellValue(Array(43,41))
  )

  val funcLib = new BasicFunctionLibrary

  println("#-- data:")
  println("\n  rows:")
  rowList.foreach(e => println(s"  ${e._1} : ${e._2}"))
  println("\n  values:")
  cellValues.foreach(e => println(s"  ${e._1} = ${e._2}"))

  //--- aux funcs

  def compile [T <: CellValue](parser: CellExpressionParser, col: Column, row: Row[T], formula: String): CellExpression[T] = {
    try {
      val ce = parser.compile(col,row,formula)
      println("success: " + ce)
      ce
    } catch {
      case x: Throwable =>
        x.printStackTrace()
        println("compile failed with " + x)
        fail(x.getMessage)
    }
  }

  def evalFor [T <: CellValue](ctx: EvalContext, expr: CellExpression[T]): T = {
    val res = expr.eval(ctx)
    println(s" --> ${ctx.currentRow.id} = $res")
    res
  }

  //--- the tests

  "a CellExpressionParser" should "parse a simple function formula into a CellExpression that can be evaluated" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)
    val formula = "(rsum ../r1 ../r2)"

    println(s"\n#-- function with explicit column-local cell references: '$formula'")

    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c1)
    ctx.setCurrentRow(r3)

    val expr: CellExpression[DoubleCellValue] = compile(p, c1,r3, formula)
    val res: DoubleCellValue = evalFor(ctx, expr)
    assert(res.value == 42.42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // f1,f2
  }

  "a CellExpressionParser" should "parse a function formula with cell patterns" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)
    val formula = "(rsum ../r{1,2})"

    println(s"\n#-- function with cell pattern: '$formula'")

    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c1)
    ctx.setCurrentRow(r3)

    val expr: CellExpression[DoubleCellValue] = compile(p, c1,r3, formula)
    val res: DoubleCellValue = evalFor(ctx, expr)
    assert(res.value == 42.42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // f1,f2
  }

  "a CellExpressionParser" should "parse a function formula with column reference patterns" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)
    val formula = "(iavg ../c{1,2}@.)"

    println(s"\n#-- function with column pattern: '$formula'")

    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues, c2.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c3)
    ctx.setCurrentRow(r1)

    val expr: CellExpression[LongCellValue] = compile(p, c3,r1, formula)
    val res: LongCellValue = evalFor(ctx, expr)
    assert(res.value == 42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // f1,f2
  }

  "a CellExpressionParser" should "parse a nested formula" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)
    val formula = "(rsum ../r2 (imax ../r{1,4}) -0.42)"

    println(s"\n#-- function with nested expression args: '$formula'")

    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c1)
    ctx.setCurrentRow(r3)

    val expr: CellExpression[DoubleCellValue] = compile(p, c1,r3, formula)
    val res: DoubleCellValue = evalFor(ctx, expr)
    assert(res.value == 43.0)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 3) // r1,r2,r4
  }

  "a CellExpressionParser" should "parse a self-referential cell func" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)
    val formula = "(iinc ../r1)"

    println(s"\n#-- function with self-reference: '$formula'")

    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c1)
    ctx.setCurrentRow(r4)

    val expr: CellExpression[LongCellValue] = compile(p, c1,r4, formula)
    val res: LongCellValue = evalFor(ctx, expr)
    assert(res.value == 85)
    ctx.setCurrentCellValue(res)
    assert(ctx.currentCells(r4.id).asInstanceOf[LongCellValue].toLong == 85)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 1) // r1
  }

  "a CellExpressionParser" should "parse array pushn func" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)

    val formula = "(ilpushn ../r4 2)"
    val expr: CellExpression[LongListCellValue] = compile(p, c1,r5, formula)

    println(s"\n#-- array pushn function: '$formula'")
    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c1)

    ctx.setCurrentRow(r5)
    val res: LongListCellValue = evalFor(ctx,expr)
    assert(res.value(0) == 43 && res.length == 2)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 1) // r4
  }

  "a CellExpressionParser" should "parse array avg func" in {
    val p = new CellExpressionParser(columnList,rowList,funcLib)

    val formula = "(ilavg ../r5)"
    val expr: CellExpression[LongCellValue] = compile(p, c1,r1, formula)

    println(s"\n#-- array avg function: '$formula'")
    val ctx = new BasicEvalContext( p"thisNode", columnList, rowList, mutable.Map(c1.id -> cellValues))
    ctx.setEvalDate(DateTime.parseYMDT("2020-09-10T16:30:00"))
    ctx.setCurrentColumn(c1)

    ctx.setCurrentRow(r1)
    val res: LongCellValue = evalFor(ctx,expr)
    assert(res.value == 42)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 1) // r4
  }
}
