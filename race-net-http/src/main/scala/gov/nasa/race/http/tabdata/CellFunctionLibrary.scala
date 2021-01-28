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

import gov.nasa.race.uom.{DateTime, Time}

/**
  * extensible base container for CellFunction instances
  */
class CellFunctionLibrary() {
  val funcLib: Map[String,CellFunction[_]] = registerFuncs(
    IntMin,IntMax,IntSum,IntAvg,IntAvgReal, IntCellInc,
    IntWithin,
    RealMin,RealMax,RealSum,RealAvg, RealSet,
    AsInteger,AsReal,
    IntListCellPushN, IntListCellAvgInt, IntListCellAvgReal,
    OlderThanHours,OlderThanMinutes
  )

  def registerFuncs (fs: CellFunction[_]*): Map[String,CellFunction[_]] = {
    fs.foldLeft(Map.empty[String,CellFunction[_]])( (map,f) => map + (f.name -> f))
  }

  def get (funcName: String): Option[CellFunction[_]] = funcLib.get(funcName)
}


//---------------- basic function instances

//--- Integer functions

object IntMin extends CellFunction[Long] with ArityN with IntegerDomain with IntegerCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = evaluateArgs(ctx, args).min
}

object IntMax extends CellFunction[Long] with ArityN with IntegerDomain with IntegerCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = evaluateArgs(ctx, args).max
}

object IntSum extends CellFunction[Long] with ArityN with IntegerDomain with IntegerCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = evaluateArgs(ctx,args).foldLeft(0L)( (sum,n) => sum+n )
}

object IntAvg extends CellFunction[Long] with ArityN with IntegerDomain with IntegerCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = {
    (evaluateArgs(ctx,args).foldLeft(0L)((sum, n) => sum+n ).toDouble / args.size).round
  }
}

object IntAvgReal extends CellFunction[Double] with ArityN with IntegerDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = {
    (evaluateArgs(ctx,args).foldLeft(0L)((sum, n) => sum+n ).toDouble / args.size)
  }
}

object IntCellInc extends CellFunction[Long] with Arity2 with HeterogenousDomain with IntegerCoDomain {
  def checkArgTypesError(args: Seq[CellExpression[_]]): Option[String] = {
    checkArgTypes2[IntegerCellRef, IntegerExpression](args)
  }

  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = {
    val v: Long = args(0).asInstanceOf[IntegerCellRef].eval(ctx)
    val inc: Long = args(1).asInstanceOf[IntegerExpression].eval(ctx)
    v + inc
  }
}

object IntWithin extends CellFunction[Boolean] with Arity3 with IntegerDomain with BoolCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Boolean = {
    val v: Long = args(0).asInstanceOf[IntegerExpression].eval(ctx)
    val vMin: Long = args(1).asInstanceOf[IntegerExpression].eval(ctx)
    val vMax: Long = args(2).asInstanceOf[IntegerExpression].eval(ctx)
    v >= vMin && v <= vMax
  }
}


//--- Real functions

object RealMin extends CellFunction[Double] with ArityN with RealDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = evaluateArgs(ctx, args).min
}

object RealMax extends CellFunction[Double] with ArityN with RealDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = evaluateArgs(ctx, args).max
}

object RealSum extends CellFunction[Double] with ArityN with RealDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = evaluateArgs(ctx,args).foldLeft(0.0)( (sum,n) => sum+n )
}

object RealAvg extends CellFunction[Double] with ArityN with RealDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = evaluateArgs(ctx,args).foldLeft(0.0)( (sum,n) => sum+n ) / args.size
}

object RealSet extends CellFunction[Double] with Arity1 with RealDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = {
    args(0).asInstanceOf[RealCellRef].eval(ctx)
  }
}

/**
  * bounded stack (prepend element to array of max size, pushing out tail elements)
  * f( IntegerListCellRef, Integer, Integer)
  */
object IntListCellPushN extends CellFunction[IntegerList] with Arity3 with HeterogenousDomain with IntegerListCoDomain {

  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = {
    checkArgTypes3[ IntegerListCellRef, IntegerExpression, IntegerExpression ](args)
  }

  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): IntegerList = {
    val intList = args(0).asInstanceOf[IntegerListCellRef].eval(ctx)
    val v = args(1).asInstanceOf[IntegerExpression].eval(ctx)
    val max = args(2).asInstanceOf[IntegerExpression].eval(ctx)
    intList.prependBounded(v,max.toInt)
  }
}

object IntListCellAvgInt extends CellFunction[Long] with Arity1 with HeterogenousDomain with IntegerCoDomain {

  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = {
    checkArgType[IntegerListCellRef](args(0))
  }

  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = {
    val vs: Array[Long] = args(0).asInstanceOf[IntegerListCellRef].eval(ctx).elements
    (vs.foldLeft(0.0){ (acc,v) => acc + v} / vs.length).round
  }
}

object IntListCellAvgReal extends CellFunction[Double] with Arity1 with HeterogenousDomain with RealCoDomain {

  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = {
    checkArgType[IntegerListCellRef](args(0))
  }

  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = {
    val vs: Array[Long] = args(0).asInstanceOf[IntegerListCellRef].eval(ctx).elements
    vs.foldLeft(0.0){ (acc,v) => acc + v} / vs.length
  }
}

//... and respective RealList functions to follow

//--- actuality

trait ActualityFunction extends Arity2 with HeterogenousDomain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = {
    checkArgTypes2[ CellRef[_], IntegerExpression](args)
  }
}

class OutdatedCheck (f: Long=>Time) extends CellFunction[Boolean] with ActualityFunction with BoolCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Boolean = {
    val cv: CellValue[_] = args(0).asInstanceOf[CellRef[_]].evalToCellValue(ctx)
    val maxAge: Time = f(args(1).asInstanceOf[IntegerExpression].eval(ctx))
    val age: Time = DateTime.now.timeSince(cv.date)
    age > maxAge
  }
}

object OlderThanMinutes extends OutdatedCheck (Time.Minutes)

object OlderThanHours extends OutdatedCheck (Time.Hours)


//--- type conversion

object AsInteger extends CellFunction[Long] with Arity1 with RealDomain with IntegerCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Long = evaluateArg(ctx,args(0)).toLong
}

object AsReal extends CellFunction[Double] with Arity1 with IntegerDomain with RealCoDomain {
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): Double = evaluateArg(ctx,args(0)).toDouble
}




//... and many more