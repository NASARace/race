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

/**
  * a collection of function definitions that can be looked up by name
  */
trait CellFunctionLibrary {

  protected var map: Map[String,CellFunctionFactory] = Map.empty

  def add(cf: CellFunctionFactory): Unit = {
    map = map + (cf.id -> cf)
  }

  def get(funName: String): Option[CellFunctionFactory] = map.get(funName)
}

/**
  * something that creates CellFunctions
  */
trait CellFunctionFactory {
  val id: String = {
    val name = getClass.getSimpleName
    if (name.endsWith("$")) name.substring(0,name.length-1) else name
  }

  def apply (args: Seq[AnyCellExpression]): AnyCellFunction

  def checkArity (nArgs: Int): Boolean

  // those are set by mixin in Domain/CoDomain traits
  def returnType: Class[_]
  def argTypeAt (argIdx: Int): Class[_]

  def checkReturnType (cls: Class[_]): Boolean = cls.isAssignableFrom(returnType)
  def checkArgType (argIdx: Int, cls: Class[_]): Boolean = cls.isAssignableFrom(argTypeAt(argIdx))

  override def toString: String = s"$id:${getClass.getSimpleName}"
}

//--- arity constraint mixins for CellFunction factories

trait AnyArity {
  def checkArity (nArgs: Int): Boolean = true // accepts any number or arguments
}
trait AnyArityDeps {
  val args: Seq[CellExpression[_]]
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = args.foldLeft(acc)( (a,e) => e.dependencies(a))
}

trait Arity0 {
  def checkArity (nArgs: Int): Boolean = nArgs == 0
}
trait Arity0Deps {
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] =acc
}

trait Arity1 {
  def checkArity (nArgs: Int): Boolean = nArgs == 1
}
trait Arity1Deps {
  val arg0: CellExpression[_]
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = arg0.dependencies(acc)
}

trait Arity2 {
  def checkArity (nArgs: Int): Boolean = nArgs == 2
}
trait Arity2Deps {
  val arg0: CellExpression[_]
  val arg1: CellExpression[_]
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = arg1.dependencies( arg0.dependencies(acc))
}

trait Arity3 {
  def checkArity (nArgs: Int): Boolean = nArgs == 3
}
trait Arity3Deps {
  val arg0: CellExpression[_]
  val arg1: CellExpression[_]
  val arg2: CellExpression[_]
  def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = arg2.dependencies( arg1.dependencies( arg0.dependencies(acc)))
}

trait ArityN {
  def checkArity (nArgs: Int): Boolean = nArgs > 0 // any positive number
}

//--- common domains

trait HomogenousDomain {
  val argType: Class[_]

  def argTypeAt (idx: Int) = argType
}

trait NumDomain extends HomogenousDomain {
  val argType = classOf[NumCellValue]
}

trait LongDomain extends HomogenousDomain {
  val argType = classOf[LongCellValue]
}

trait DoubleDomain extends HomogenousDomain {
  val argType = classOf[DoubleCellValue]
}

//--- common co-domains

trait NumCoDomain {
  val returnType = classOf[NumCellValue]
}

trait LongCoDomain {
  val returnType = classOf[LongCellValue]
}

trait DoubleCoDomain {
  val returnType = classOf[DoubleCellValue]
}

trait BooleanCoDomain {
  val returnType = classOf[BooleanCellValue]
}


/**
  * a default FunctionLibrary
  */
class BasicFunctionLibrary extends CellFunctionLibrary {

  /**
    * a function that accepts any number of LongCell args and returns the sum as a rounded LongCell
    */
  object suml extends CellFunctionFactory with LongCoDomain with LongDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new suml(args.asInstanceOf[Seq[LongExpr]])
  }
  add(suml)

  class suml(val args: Seq[LongExpr]) extends LongFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
      LongCellValue(args.foldLeft(0L){ (acc, ce) => acc + ce.eval.toLong })(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts any number of NumExpr args and returns the sum as a DoubleCell
    */
  object sumd extends CellFunctionFactory with DoubleCoDomain with NumDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new sumd(args.asInstanceOf[Seq[AnyNumExpr]])
  }
  add(sumd)

  class sumd(val args: Seq[AnyNumExpr]) extends DoubleFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext) = {
      DoubleCellValue(args.foldLeft(0.0){ (acc, ce) => acc + ce.eval.toDouble })(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts any number of LongExpr args and returns the maximum as a rounded LongCell
    */
  object maxl extends CellFunctionFactory with LongCoDomain with LongDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new maxl(args.asInstanceOf[Seq[LongExpr]])
  }
  add(maxl)

  class maxl(val args: Seq[LongExpr]) extends LongFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext) = {
      LongCellValue(args.foldLeft(Long.MinValue){ (max, ce) =>
        val n = ce.eval.toLong
        if (n > max) n else max
      })(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts any number of DoubleExpr args and returns the maximum as a DoubleCell
    */
  object maxd extends CellFunctionFactory with DoubleCoDomain with NumDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new maxd(args.asInstanceOf[Seq[AnyNumExpr]])
  }
  add(maxd)

  class maxd(val args: Seq[AnyNumExpr]) extends DoubleFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext) = {
      DoubleCellValue(args.foldLeft(Double.MinValue){ (max, ce) =>
        val n = ce.eval.toDouble
        if (n > max) n else max
      })(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that calculates averages
    */

  object avgl extends CellFunctionFactory with LongCoDomain with LongDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new avgl(args.asInstanceOf[Seq[LongExpr]])
  }
  add(avgl)

  class avgl(val args: Seq[LongExpr]) extends LongFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext) = {
      val sum = args.foldLeft(0.0){ (acc, ce) => acc + ce.eval.toDouble }
      LongCellValue((sum/args.size).round)(ctx.evalDate)
    }
    def argExprs = args
  }

  object avgd extends CellFunctionFactory with DoubleCoDomain with NumDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new avgd(args.asInstanceOf[Seq[AnyNumExpr]])
  }
  add(avgd)

  class avgd(val args: Seq[AnyNumExpr]) extends DoubleFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext) = {
      val sum = args.foldLeft(0.0){ (acc, ce) => acc + ce.eval.toDouble }
      DoubleCellValue(sum/args.size)(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts one LongExpr argument and adds it to the currently evaluated cell
    */
  object accl extends CellFunctionFactory with LongCoDomain with LongDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new accl(args.head.asInstanceOf[LongExpr])
  }
  add(accl)

  class accl(val arg0: LongExpr) extends LongFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext) = {
      LongCellValue(ctx.currentCellValue.asInstanceOf[LongCellValue].toLong + arg0.eval.toLong)(ctx.evalDate)
    }
    def argExprs = Seq(arg0)
  }

  /**
    * a function that accepts one DoubleExpr argument and adds it to the currently evaluated cell
    */
  object accd extends CellFunctionFactory with DoubleCoDomain with DoubleDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new accd(args.head.asInstanceOf[DoubleExpr])
  }
  add(accd)

  class accd(val arg0: DoubleExpr) extends DoubleFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext) = {
      DoubleCellValue(ctx.currentCellValue.asInstanceOf[DoubleCellValue].toDouble + arg0.eval.toDouble)(ctx.evalDate)
    }
    def argExprs = Seq(arg0)
  }

  /**
    * a function that takes a BooleanExpr and two LongExpr and returns the second or third eval based on the cond expr
    */
  object ifl extends CellFunctionFactory with LongCoDomain with Arity3 {
    def argTypeAt (argIdx: Int): Class[_] = {
      argIdx match {
        case 0 => classOf[BooleanCellValue]
        case 1 | 2 => classOf[LongCellValue]
        case n => sys.error(s"wrong function arity of 'ifl' (expected 3 found $n")
      }
    }

    def apply (args: Seq[AnyCellExpression]) = new ifl(args(0).asInstanceOf[BooleanExpr], args(1).asInstanceOf[LongExpr], args(2).asInstanceOf[LongExpr])
  }
  add(ifl)

  class ifl (val arg0: BooleanExpr, val arg1: LongExpr, val arg2: LongExpr) extends LongFunc  with Arity3Deps {
    def eval (implicit ctx: EvalContext) = {
      if (arg0.eval.toBoolean) {
        arg1.eval
      } else {
        arg2.eval
      }
    }
    def argExprs = Seq(arg0,arg1,arg2)
  }

  /**
    * a function that takes two NumExprs and returns a BooleanCell based on if the first arg eval > second arg eval
    */
  object gt extends CellFunctionFactory with BooleanCoDomain with NumDomain with Arity2 {
    def apply (args: Seq[AnyCellExpression]) = new gt(args.head.asInstanceOf[AnyNumExpr], args.last.asInstanceOf[AnyNumExpr])
  }
  add(gt)

  class gt (val arg0: AnyNumExpr, val arg1: AnyNumExpr) extends BooleanFunc with Arity2Deps {
    def eval (implicit ctx: EvalContext) = {
      BooleanCellValue(arg0.eval > arg1.eval)(ctx.evalDate)
    }
    def argExprs = Seq(arg0,arg1)
  }
}
