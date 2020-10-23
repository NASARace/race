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
  * an extensible default FunctionLibrary
  *
  * each entry consists of a CellFunctionFactory companion object and a corresponding class encapsulating
  * the type specific eval() function
  *
  * TODO - many more categories and type variants missing
  */
class BasicFunctionLibrary extends CellFunctionLibrary {

  /**
    * a function that accepts any number of LongCell args and returns the sum as a rounded LongCell
    */
  object isum extends CellFunctionFactory with LongCoDomain with LongDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new isum(args.asInstanceOf[Seq[LongExpr]])
  }
  add(isum)

  class isum(val args: Seq[LongExpr]) extends LongFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
      LongCellValue(args.foldLeft(0L){ (acc, ce) => acc + ce.eval.toLong })(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts any number of NumExpr args and returns the sum as a DoubleCell
    */
  object rsum extends CellFunctionFactory with DoubleCoDomain with NumDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new rsum(args.asInstanceOf[Seq[AnyNumExpr]])
  }
  add(rsum)

  class rsum(val args: Seq[AnyNumExpr]) extends DoubleFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): DoubleCellValue = {
      DoubleCellValue(args.foldLeft(0.0){ (acc, ce) => acc + ce.eval.toDouble })(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts any number of LongExpr args and returns the maximum as a rounded LongCell
    */
  object imax extends CellFunctionFactory with LongCoDomain with LongDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new imax(args.asInstanceOf[Seq[LongExpr]])
  }
  add(imax)

  class imax(val args: Seq[LongExpr]) extends LongFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
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
  object rmax extends CellFunctionFactory with DoubleCoDomain with NumDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new rmax(args.asInstanceOf[Seq[AnyNumExpr]])
  }
  add(rmax)

  class rmax(val args: Seq[AnyNumExpr]) extends DoubleFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): DoubleCellValue = {
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

  object iavg extends CellFunctionFactory with LongCoDomain with LongDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new iavg(args.asInstanceOf[Seq[LongExpr]])
  }
  add(iavg)

  class iavg(val args: Seq[LongExpr]) extends LongFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
      val sum = args.foldLeft(0.0){ (acc, ce) => acc + ce.eval.toDouble }
      LongCellValue((sum/args.size).round)(ctx.evalDate)
    }
    def argExprs = args
  }

  object ravg extends CellFunctionFactory with DoubleCoDomain with NumDomain with AnyArity {
    def apply (args: Seq[AnyCellExpression]) = new ravg(args.asInstanceOf[Seq[AnyNumExpr]])
  }
  add(ravg)

  class ravg(val args: Seq[AnyNumExpr]) extends DoubleFunc with AnyArityDeps {
    def eval (implicit ctx: EvalContext): DoubleCellValue = {
      val sum = args.foldLeft(0.0){ (acc, ce) => acc + ce.eval.toDouble }
      DoubleCellValue(sum/args.size)(ctx.evalDate)
    }
    def argExprs = args
  }

  /**
    * a function that accepts one LongExpr argument and adds it to the currently evaluated cell
    */
  object iinc extends CellFunctionFactory with LongCoDomain with LongDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new iinc(args.head.asInstanceOf[LongExpr])
  }
  add(iinc)

  class iinc(val arg0: LongExpr) extends LongFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
      LongCellValue(ctx.currentCellValue.asInstanceOf[LongCellValue].toLong + arg0.eval.toLong)(ctx.evalDate)
    }
    def argExprs = Seq(arg0)
  }

  /**
    * a function that accepts one NumExpr argument and adds it to the currently evaluated cell
    */
  object rinc extends CellFunctionFactory with DoubleCoDomain with NumDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new rinc(args.head.asInstanceOf[AnyNumExpr])
  }
  add(rinc)

  class rinc(val arg0: AnyNumExpr) extends DoubleFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext): DoubleCellValue = {
      DoubleCellValue(ctx.currentCellValue.asInstanceOf[DoubleCellValue].toDouble + arg0.eval.toDouble)(ctx.evalDate)
    }
    def argExprs = Seq(arg0)
  }

  //--- setter functions

  /**
    * set current cell from provided integer expression
    */
  object iset extends CellFunctionFactory with LongCoDomain with LongDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new iset(args.head.asInstanceOf[LongExpr])
  }
  add(iset)

  class iset(val arg0: LongExpr) extends LongFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
      LongCellValue(arg0.eval.toLong)(ctx.evalDate)
    }
    def argExprs = Seq(arg0)
  }

  /**
    * set current cell from provided long expression
    */
  object rset extends CellFunctionFactory with DoubleCoDomain with NumDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new rset(args.head.asInstanceOf[AnyNumExpr])
  }
  add(rset)

  class rset(val arg0: AnyNumExpr) extends DoubleFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext): DoubleCellValue = {
      DoubleCellValue(arg0.eval.toDouble)(ctx.evalDate)
    }
    def argExprs = Seq(arg0)
  }

  //... and other CV types

  /**
    * a function that takes a BooleanExpr and two LongExpr and returns the second or third eval based on the cond expr
    */
  object iif extends CellFunctionFactory with LongCoDomain with Arity3 {
    def argTypeAt (argIdx: Int): Class[_] = {
      argIdx match {
        case 0 => classOf[BooleanCellValue]
        case 1 | 2 => classOf[LongCellValue]
        case n => sys.error(s"wrong function arity of 'ifl' (expected 3 found $n")
      }
    }

    def apply (args: Seq[AnyCellExpression]) = new iif(args(0).asInstanceOf[BooleanExpr], args(1).asInstanceOf[LongExpr], args(2).asInstanceOf[LongExpr])
  }
  add(iif)

  class iif(val arg0: BooleanExpr, val arg1: LongExpr, val arg2: LongExpr) extends LongFunc  with Arity3Deps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
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
    def eval (implicit ctx: EvalContext): BooleanCellValue = {
      BooleanCellValue(arg0.eval > arg1.eval)(ctx.evalDate)
    }
    def argExprs = Seq(arg0,arg1)
  }

  // ... and more comparisons

  //--- array functions

  /**
    * push a LongCellValue into a stack of a bounded size
    */
  object ilpushn extends CellFunctionFactory with LongListCoDomain with LongDomain with Arity2 {
    def apply (args: Seq[AnyCellExpression]) = new ilpushn(args.head.asInstanceOf[LongExpr], args.last.asInstanceOf[LongExpr])
  }
  add(ilpushn)

  class ilpushn (val arg0: LongExpr, val arg1: LongExpr) extends LongListFunc with Arity2Deps {
    def eval (implicit ctx: EvalContext): LongListCellValue = {
      val curCv = ctx.currentCellValue.asInstanceOf[LongListCellValue]
      curCv.pushBounded( arg0.eval(ctx).value, arg1.eval(ctx).value.toInt)(ctx.evalDate)
    }
    def argExprs = Seq(arg0,arg1)
  }

  /**
    * compute the average of a LongListCellValue
    */
  object ilavg extends CellFunctionFactory with LongCoDomain with LongListDomain with Arity1 {
    def apply (args: Seq[AnyCellExpression]) = new ilavg(args.head.asInstanceOf[LongListExpr])
  }
  add(ilavg)

  class ilavg (val arg0: LongListExpr) extends LongFunc with Arity1Deps {
    def eval (implicit ctx: EvalContext): LongCellValue = {
      val laCv = arg0.eval(ctx)
      val avg: Long = laCv.foldLeft(0L)( (acc,v) => acc + v) / laCv.length
      LongCellValue(avg)(ctx.evalDate)
    }

    def argExprs = Seq(arg0)
  }
}
