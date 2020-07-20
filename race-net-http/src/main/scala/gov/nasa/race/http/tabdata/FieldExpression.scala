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

import scala.collection.immutable.ListMap
import scala.util.matching.Regex
import scala.util.parsing.combinator._

object FieldExpression {
  var functions = Map.empty[String,FunFactory]

  Seq(Sum, Max, Acc).foreach {ff=> functions = functions + (ff.id -> ff)}

}

/*
 sum( `/cat_A/.+`)
 acc( /cat_B/field )
 mul( /cat_A/x, sub(/cat_B/y,/cat_X/z) )

   expr ::=  const | fieldRef | fun
   fun ::= '(' funId funArgs ')'
   funArgs ::= { fun | const | fieldRef | fieldRefPattern }
   const ::= integer | rational
 */

class FieldExpressionParser (fields: ListMap[String,Field], functions: Map[String,FunFactory]) extends RegexParsers {

  //--- tokens
  def integer: Parser[LongConst]    = """\d+""".r ^^ { s=> LongConst(s.toLong) }
  def rational: Parser[DoubleConst] = """-?\d*\.\d+""".r ^^ { s=> DoubleConst(s.toDouble) }
  def funId: Parser[String] = """[a-z][0-9A-Za-z_]*""".r

  //--- productions
  def const: Parser[FieldExpression] = rational | integer

  def fieldRef: Parser[FieldRef]    = """[a-zA-Z0-9_/$:]+""".r ^^ { id=>
    if (fields.contains(id)) FieldRef(id)
    else throw new RuntimeException(s"unknown field $id")
  }

  def fieldRefPattern: Parser[Regex]     = "`" ~> """[^`]+""".r <~ "`" ^^ { new Regex(_) }

  def funArgs: Parser[Seq[FieldExpression]] = rep( fun | const | fieldRef | fieldRefPattern ) ^^ { list=>
    var res = Seq.empty[FieldExpression]
    list.foreach { e=>
      e match {
        case fe: FieldExpression => res = res :+ fe
        case re: Regex =>
          val matchList = fields.foldLeft(Seq.empty[FieldExpression]) { (acc,e) =>
            if (re.matches(e._1)) acc :+ FieldRef(e._1) else acc
          }
          if (matchList.isEmpty) throw new RuntimeException(s"no field match for pattern `${re.regex}`")
          res = res :++ matchList
        case _ => // can't get here
      }
    }
    res
  }

  def fun: Parser[Fun] = "(" ~> funId ~ funArgs <~ ")" ^^ { res =>
    functions.get(res._1) match {
      case Some(ff) => ff(res._2)
      case None => throw new RuntimeException(s"unknown function ${res._1}")
    }
  }

  // no fieldRefPattern - we need this to eval to a single FieldValue
  def expr: Parser[FieldExpression] = fun | const | fieldRef

}


//--- the AST elements
/**
  * what is needed to get FieldValues from a FieldValueSource
  */
trait EvalContext {
  def contextField: Field
  def fieldValues: Map[String,FieldValue]

  def contextFieldValue: FieldValue = fieldValues.get(contextField.id) match {
    case Some(fv) => fv
    case None => UndefinedValue
  }
}

/**
  * mostly for stand-alone debugging purposes
  */
class SimpleEvalContext (var contextField: Field, var fieldValues: Map[String,FieldValue]) extends EvalContext

trait FieldExpression extends Any {
  def eval (implicit ctx: EvalContext): FieldValue

  def dependencies (acc: Set[String] = Set.empty[String]): Set[String]
}

case class FieldRef (id: String) extends FieldExpression {

  def eval (implicit ctx: EvalContext): FieldValue = {
    ctx.fieldValues.get(id) match {
      case Some(fv) => fv
      case None => UndefinedValue
    }
  }
  override def toString: String = id

  def dependencies (acc: Set[String]): Set[String] = acc + id
}

trait ConstFieldExpression extends FieldExpression {
  def dependencies (acc: Set[String]): Set[String] = acc // does not add any dependencies
}

case class LongConst (value: Long) extends ConstFieldExpression {
  def eval (implicit ctx: EvalContext): FieldValue = LongValue(value)
  override def toString: String = value.toString
}

case class DoubleConst (value: Double) extends ConstFieldExpression {
  def eval (implicit ctx: EvalContext): FieldValue = DoubleValue(value)
  override def toString: String = value.toString
}

//--- function objects

trait FunFactory {
  val id: String
  def checkArity (nArgs: Int): Boolean
  def apply (args: Seq[FieldExpression]): Fun
}

trait Fun extends FieldExpression {
  val id: String
  val args: Seq[FieldExpression]

  override def toString: String = s"($id ${args.mkString(" ")})"

  def dependencies (acc: Set[String]): Set[String] = {
    args.foldLeft(acc)( (a,e) => e.dependencies(a))
  }
}

trait AnyArity {
  def checkArity (nArgs: Int): Boolean = true // accepts any number or arguments
}

trait Arity0 {
  def checkArity (nArgs: Int): Boolean = nArgs == 0
}

trait Arity1 {
  def checkArity (nArgs: Int): Boolean = nArgs == 1
}

trait Arity2 {
  def checkArity (nArgs: Int): Boolean = nArgs == 2
}

//--- standard functions

object Sum extends FunFactory with AnyArity {
  val id = "sum"
  def apply (args: Seq[FieldExpression]) = new Sum(args)
}
class Sum (val args: Seq[FieldExpression]) extends Fun {
  val id = Sum.id

  def eval (implicit ctx: EvalContext): FieldValue = {
    args.foldLeft[FieldValue](LongValue(0))( (sum,fvs) => sum + fvs.eval(ctx) )
  }
}

object Max extends FunFactory with AnyArity {
  val id = "max"
  def apply (args: Seq[FieldExpression]) = new Max(args)
}
class Max (val args: Seq[FieldExpression]) extends Fun {
  val id = Max.id
  def eval (implicit ctx: EvalContext): FieldValue = {
    args.foldLeft[FieldValue](LongValue(0))( (max,fvs) => {
      val v = fvs.eval(ctx)
      if (v > max) v else max
    })
  }
}

/**
  * add the (single) argument to the currently evaluated field
  */
object Acc extends FunFactory with Arity1 {
  val id = "acc"
  def apply (args: Seq[FieldExpression]) = new Acc(args)
}
class Acc (val args: Seq[FieldExpression]) extends Fun {
  val id = Acc.id
  def eval (implicit ctx: EvalContext): FieldValue = {
    ctx.contextFieldValue + args(0).eval(ctx)
  }
}