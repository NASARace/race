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

trait BooleanDomain extends HomogenousDomain {
  val argType = classOf[BooleanCellValue]
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

trait LongListDomain extends HomogenousDomain {
  val argType = classOf[LongListCellValue]
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

trait LongListCoDomain {
  val returnType = classOf[LongListCellValue]
}




