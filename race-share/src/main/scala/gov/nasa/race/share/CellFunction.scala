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

import scala.reflect.{ClassTag, classTag}

trait NamedFunction {
  def name: String
}

/**
  * abstract base for function objects that can be type-checked
  *
  * note these can be shared between different CellFunctionCalls (the corresponding CellExpressions that also
  * capture respective arguments), i.e. we only need a singleton per CellFunction
  */
abstract class CellFunction[T: ClassTag] extends NamedFunction {
  def returnType: Class[_] = classTag[T].runtimeClass

  def name: String = {
    val s = getClass.getSimpleName
    val len = s.length
    if (s.charAt(len-1) == '$') s.substring(0, len-1) else s
  }

  def checkArgError (args: Seq[CellExpression[_]]): Option[String] = checkArityError(args.size).orElse( checkArgTypesError(args))

  def checkArityError(nArgs: Int): Option[String] // -> Arity trait
  def checkArgTypesError(args: Seq[CellExpression[_]]): Option[String]  // -> Domain trait

  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[T]

  def genCall (args: Seq[CellExpression[_]]): Either[String,CellFunctionCall[T]] = {
    checkArgError(args) match {
      case Some(error) => Left(error)
      case None => Right(createCall(args))
    }
  }
  def eval (ctx: EvalContext, args: Seq[CellExpression[_]]): T
}

//------------ arity traits

trait Arity1 extends NamedFunction {
  def checkArityError (nArgs: Int): Option[String] = if (nArgs != 1) Some(s"function $name expects one argument, got $nArgs") else None
}

trait ArityGe1 extends NamedFunction {
  def checkArityError (nArgs: Int): Option[String] = if (nArgs < 1) Some(s"function $name expects at least one argument, got $nArgs") else None
}

trait Arity2 extends NamedFunction {
  def checkArityError (nArgs: Int): Option[String] = if (nArgs != 2) Some(s"function $name expects two arguments, got $nArgs") else None
}

trait Arity3 extends NamedFunction {
  def checkArityError (nArgs: Int): Option[String] = if (nArgs != 3) Some(s"function $name expects three arguments, got $nArgs") else None
}

trait ArityGe2 extends NamedFunction {
  def checkArityError (nArgs: Int): Option[String] = if (nArgs < 2) Some(s"function $name expects at least two arguments, got $nArgs") else None
}

trait ArityN extends NamedFunction  {
  def checkArityError (nArgs: Int): Option[String] = None
}

//------------ co-domain traits

trait IntegerCoDomain extends CellFunction[Long] {
  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[Long] = IntegerCall(this,args)
}

trait RealCoDomain extends CellFunction[Double] {
  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[Double] = RealCall(this,args)
}

trait BoolCoDomain extends CellFunction[Boolean] {
  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[Boolean] = BoolCall(this,args)
}

trait StringCoDomain extends CellFunction[String] {
  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[String] = StringCall(this,args)
}

trait IntegerListCoDomain extends CellFunction[IntegerList] {
  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[IntegerList] = IntegerListCall(this,args)
}

trait RealListCoDomain extends CellFunction[RealList] {
  def createCall (args: Seq[CellExpression[_]]): CellFunctionCall[RealList] = RealListCall(this,args)
}

//------------ domain traits

trait Domain extends NamedFunction {

  def checkArgTypes[E: ClassTag](args: Seq[CellExpression[_]]): Option[String] = {
    args.find( a=> !classTag[E].runtimeClass.isAssignableFrom(a.getClass)).map( a=> s"function '$name' argument '$a' not a ${classTag[E]} instance")
  }

  def checkArgTypeCond (args: Seq[CellExpression[_]])(f: Class[_]=>Boolean): Option[String] = {
    var i=1
    args.foreach { ce =>
      val ceType = ce.cellType
      if (!f(ceType)) return Some(s"function '$name' has incompatible argument $i of type ${ceType}")
      i += 1
    }
    None
  }
}

trait HeterogenousDomain extends Domain {

  //--- convenience functions for custom arg type checks
  def checkArgType[E: ClassTag](e: CellExpression[_]): Option[String] = {
    if (classTag[E].runtimeClass.isAssignableFrom(e.getClass)) None else Some(s"function '$name' argument '$e' not a ${classTag[E]} instance")
  }

  def checkArgTypes1[E1: ClassTag](args: Seq[CellExpression[_]]): Option[String] = checkArgType[E1](args(0))

  def checkArgTypes2[E1: ClassTag, E2: ClassTag](args: Seq[CellExpression[_]]): Option[String] = {
    checkArgType[E1](args(0)).orElse( checkArgType[E2](args(1)))
  }

  def checkArgTypes3[E1: ClassTag, E2: ClassTag, E3: ClassTag](args: Seq[CellExpression[_]]): Option[String] = {
    checkArgType[E1](args(0)).orElse( checkArgType[E2](args(1))).orElse( checkArgType[E3](args(2)))
  }

  //... and possibly more
}

trait IntegerDomain extends Domain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = checkArgTypes[IntegerExpression](args)

  def evaluateArgs (ctx: EvalContext, args: Seq[CellExpression[_]]): Seq[Long] = {
    args.map( _.asInstanceOf[IntegerExpression].evalToInteger(ctx))
  }

  def evaluateArg (ctx: EvalContext, arg: CellExpression[_]): Long = {
    arg.asInstanceOf[IntegerExpression].evalToInteger(ctx)
  }
}

trait RealDomain extends Domain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = checkArgTypes[NumExpression[_]](args)

  def evaluateArgs (ctx: EvalContext, args: Seq[CellExpression[_]]): Seq[Double] = {
    args.map( _.asInstanceOf[NumExpression[_]].evalToReal(ctx))
  }

  def evaluateArg (ctx: EvalContext, arg: CellExpression[_]): Double = {
    arg.asInstanceOf[RealExpression].evalToReal(ctx)
  }
}

trait BoolDomain extends Domain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = checkArgTypes[BoolExpression](args)

  def evaluateArgs (ctx: EvalContext, args: Seq[CellExpression[_]]): Seq[Boolean] = {
    args.map( _.asInstanceOf[BoolExpression].eval(ctx))
  }

  def evaluateArg (ctx: EvalContext, arg: CellExpression[_]): Boolean = {
    arg.asInstanceOf[BoolExpression].eval(ctx)
  }
}

trait StringDomain extends Domain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = checkArgTypes[StringExpression](args)

  def evaluateArgs (ctx: EvalContext, args: Seq[CellExpression[_]]): Seq[String] = {
    args.map( _.asInstanceOf[StringExpression].eval(ctx))
  }

  def evaluateArg (ctx: EvalContext, arg: CellExpression[_]): String = {
    arg.asInstanceOf[StringExpression].eval(ctx)
  }
}

trait IntegerListDomain extends Domain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = checkArgTypes[IntegerListExpression](args)

  def evaluateArgs (ctx: EvalContext, args: Seq[CellExpression[_]]): Seq[IntegerList] = {
    args.map( _.asInstanceOf[IntegerListExpression].eval(ctx))
  }

  def evaluateArg (ctx: EvalContext, arg: CellExpression[_]): IntegerList = {
    arg.asInstanceOf[IntegerListExpression].eval(ctx)
  }
}

trait RealListDomain extends Domain {
  def checkArgTypesError (args: Seq[CellExpression[_]]): Option[String] = checkArgTypes[RealListExpression](args)

  def evaluateArgs (ctx: EvalContext, args: Seq[CellExpression[_]]): Seq[RealList] = {
    args.map( _.asInstanceOf[RealListExpression].eval(ctx))
  }

  def evaluateArg (ctx: EvalContext, arg: CellExpression[_]): RealList = {
    arg.asInstanceOf[RealListExpression].eval(ctx)
  }
}



