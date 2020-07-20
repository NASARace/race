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

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.immutable.ListMap

/**
  * reg test for FieldExpression parsing and evaluation
  */
class FieldExpressionSpec extends AnyFlatSpec with RaceSpec {

  //--- test data

  val f1 = LongField.instantiate("f1", "this is editable field 1")
  val f2 = LongField.instantiate("f2", "this is editable field 2")
  val f3 = LongField.instantiate("f3", "this is computed field 3")

  val fields: ListMap[String,Field] = ListMap.from( Seq(f1,f2,f3).map( f=> (f.id -> f) ))

  val fieldValues: ListMap[String,FieldValue] = ListMap(
    "f1" -> LongValue(42),
    "f2" -> DoubleValue(0.42)
    // we leave f3 undefined
  )

  println("#-- data:")
  println("\n  fields:")
  fields.foreach( e => println(s"  ${e._1} : ${e._2}"))
  println("\n  values:")
  fieldValues.foreach( e => println(s"  ${e._1} = ${e._2}"))

  //--- aux funcs

  def parse (parser: FieldExpressionParser, formula: String): FieldExpression = {
    parser.parseAll(parser.expr, formula) match {
      case parser.Success(ast: FieldExpression,_) => println("success: " + ast); ast
      case parser.Failure(msg,_) => println("FAILURE: " + msg); fail(msg)
      case parser.Error(msg,_) => println("ERROR: " + msg); fail(msg)
    }
  }

  def evalFor (ctx: EvalContext, expr: FieldExpression): FieldValue = {
    val res = expr.eval(ctx)
    println(s" --> ${ctx.contextField.id} = $res")
    res
  }

  //--- the tests

  "a FieldExpressionParser" should "parse a simple function formula" in {
    val p = new FieldExpressionParser(fields,FieldExpression.functions)
    val formula = "(sum f1 f2)"

    println(s"\n#-- simple function: '$formula'")
    val expr = parse(p,formula)
    evalFor(new SimpleEvalContext(f3,fieldValues), expr)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 2) // f1,f2
  }

  "a FieldExpressionParser" should "parse field ref patterns" in {
    val p = new FieldExpressionParser(fields,FieldExpression.functions)
    val formula = "(sum `f[23]`)"

    println(s"\n#-- function with field ref pattern: '$formula'")
    val expr = parse(p,formula)
    evalFor(new SimpleEvalContext(f3,fieldValues), expr)
  }


  "a FieldExpressionParser" should "parse a nested formula" in {
    val p = new FieldExpressionParser(fields,FieldExpression.functions)
    val formula = "(sum f1 (max `f[23]`) -0.42)"

    println(s"\n--- nested function: '$formula'")
    val expr = parse(p,formula)
    evalFor(new SimpleEvalContext(f3,fieldValues), expr)

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 3) // f1,f2,f3
  }

  "a FieldExpressionParser" should "parse the 'acc' function" in {
    val p = new FieldExpressionParser(fields,FieldExpression.functions)
    val formula = "(acc f1)"

    println(s"\n--- nested function: '$formula'")
    val expr = parse(p,formula)
    evalFor(new SimpleEvalContext(f3,fieldValues), expr)
    // TBD now update the fieldValues and re-eval

    val deps = expr.dependencies()
    println(s"  dependencies: $deps")
    assert(deps.size == 1) // f1
  }
}
