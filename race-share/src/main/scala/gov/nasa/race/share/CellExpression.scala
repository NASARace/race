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

import gov.nasa.race.common.{JsonSerializable, JsonWriter}

import scala.reflect.{ClassTag, classTag}

/**
  * abstract base for all expressions that produce values which can be stored in cells (Row types ColumnData entries)
  *
  * CellExpressions are AST instances that contain everything for their evaluation except the referenced CellValues,
  * which is provided as an EvalContext argument that serves as the interface towards the data model
  *
  * supported expressions are:
  *   - CellRef symbolic references for cells
  *   - Const lexical constants
  *   - CellFunctionCall formula invocations that store their arguments
  */
abstract class CellExpression[T: ClassTag] {
  def cellType: Class[_] = classTag[T].runtimeClass

  def eval (ctx: EvalContext): T  // compute the raw value

  // override the ones that are supported
  def evalToInteger (ctx: EvalContext): Long = throw new RuntimeException(s"${this.getClass.getName} cannot eval to integer type")
  def evalToReal (ctx: EvalContext): Double = throw new RuntimeException(s"${this.getClass.getName} cannot eval to real type")
  def evalToBool (ctx: EvalContext): Boolean = throw new RuntimeException(s"${this.getClass.getName} cannot eval to boolean type")
  def evalToString (ctx: EvalContext): String = throw new RuntimeException(s"${this.getClass.getName} cannot eval to string type")


  def computeCellValue (ctx: EvalContext): CellValue[T] // compute the raw value and wrap it into a CellValue

  def dependencies (acc: Set[CellRef[_]] = Set.empty[CellRef[_]]): Set[CellRef[_]] = acc
}

trait NumExpression[T] extends CellExpression[T] {
  def evalToReal (ctx: EvalContext): Double
  def evalToInteger(ctx: EvalContext): Long
}

trait IntegerExpression extends NumExpression[Long] {
  override def evalToReal (ctx: EvalContext): Double = eval(ctx).toDouble
  override def evalToInteger(ctx: EvalContext): Long = eval(ctx)
  def computeCellValue (ctx: EvalContext): IntegerCellValue = IntegerCellValue(eval(ctx),ctx.evalDate)
}

trait RealExpression extends NumExpression[Double] {
  override def evalToReal (ctx: EvalContext): Double = eval(ctx)
  override def evalToInteger(ctx: EvalContext): Long = eval(ctx).toLong
  def computeCellValue (ctx: EvalContext): RealCellValue = RealCellValue(eval(ctx),ctx.evalDate)
}

trait BoolExpression extends CellExpression[Boolean] {
  override def evalToBool(ctx: EvalContext): Boolean = eval(ctx)
  def computeCellValue (ctx: EvalContext): BoolCellValue = BoolCellValue(eval(ctx),ctx.evalDate)
}

trait StringExpression extends CellExpression[String] {
  override def evalToString (ctx: EvalContext): String = eval(ctx)
  def computeCellValue (ctx: EvalContext): StringCellValue = StringCellValue(eval(ctx),ctx.evalDate)
}

trait LinkExpression extends CellExpression[String] {
  override def evalToString (ctx: EvalContext): String = eval(ctx)
  def computeCellValue (ctx: EvalContext): LinkCellValue = LinkCellValue(eval(ctx),ctx.evalDate)
}

//--- list expression types

trait NumListExpression[T] extends CellExpression[T] {
  def evalToIntegerList (ctx: EvalContext): IntegerList
  def evalToRealList (ctx: EvalContext): RealList
}

trait IntegerListExpression extends CellExpression[IntegerList] with NumListExpression[IntegerList] {
  def evalToIntegerList (ctx: EvalContext): IntegerList = eval(ctx)
  def evalToRealList (ctx: EvalContext): RealList = RealList(evalToReal(ctx))

  def computeCellValue (ctx: EvalContext): IntegerListCellValue = IntegerListCellValue(eval(ctx),ctx.evalDate)
}

trait RealListExpression extends CellExpression[RealList] with NumListExpression[RealList] {
  def evalToIntegerList (ctx: EvalContext): IntegerList = IntegerList(evalToInteger(ctx))
  def evalToRealList (ctx: EvalContext): RealList = eval(ctx)

  def computeCellValue (ctx: EvalContext): RealListCellValue = RealListCellValue(eval(ctx),ctx.evalDate)
}

//---------------- CellRef

abstract class CellRef[T: ClassTag] extends CellExpression[T] {
  val colId: String
  val rowId: String

  override def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = acc + this

  def evalToCellValue (ctx: EvalContext): CellValue[_] = ctx.cellValue(colId,rowId)
}

// note the eval casts are safe since CellRefs are created by their respective Row objects

case class IntegerCellRef(colId: String, rowId: String) extends CellRef[Long] with IntegerExpression {
  override def eval(ctx: EvalContext): Long = ctx.typedCellValue[IntegerCellValue](colId,rowId).value
}

case class RealCellRef (colId: String, rowId: String) extends CellRef[Double] with RealExpression {
  override def eval(ctx: EvalContext): Double = ctx.typedCellValue[RealCellValue](colId,rowId).value
}

case class BoolCellRef (colId: String, rowId: String) extends CellRef[Boolean] with BoolExpression {
  override def eval(ctx: EvalContext): Boolean = ctx.typedCellValue[BoolCellValue](colId,rowId).value
}

case class StringCellRef (colId: String, rowId: String) extends CellRef[String] with StringExpression {
  override def eval(ctx: EvalContext): String = ctx.typedCellValue[StringCellValue](colId,rowId).value
}

case class LinkCellRef (colId: String, rowId: String) extends CellRef[String] with StringExpression {
  override def eval(ctx: EvalContext): String = ctx.typedCellValue[LinkCellValue](colId,rowId).value
}

case class IntegerListCellRef (colId: String, rowId: String) extends CellRef[IntegerList] with IntegerListExpression {
  override def eval(ctx: EvalContext): IntegerList = ctx.typedCellValue[IntegerListCellValue](colId,rowId).value
}

case class RealListCellRef (colId: String, rowId: String) extends CellRef[RealList] with RealListExpression {
  override def eval(ctx: EvalContext): RealList = ctx.typedCellValue[RealListCellValue](colId,rowId).value
}

//---------------- Const

abstract class CellValueConst[T: ClassTag] extends CellExpression[T] {
  val value: T

  override def eval (ctx: EvalContext): T = value
  override def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = acc // consts don't add any dependencies
}
case class IntegerCellValueConst(value: Long) extends CellValueConst[Long] with IntegerExpression

case class RealCellValueConst(value: Double) extends CellValueConst[Double] with RealExpression

case class BoolCellValueConst(value: Boolean) extends CellValueConst[Boolean] with BoolExpression

case class StringCellValueConst(value: String) extends CellValueConst[String] with StringExpression

case class IntegerListCellValueConst(value: IntegerList) extends CellValueConst[IntegerList] with IntegerListExpression {
  def this (es: Array[Long]) = this(IntegerList(es))
}

case class RealListCellValueConst(value: RealList) extends CellValueConst[RealList] with RealListExpression {
  def this (es: Array[Double]) = this(RealList(es))
}

//---------------- CellFunction calls

abstract class CellFunctionCall[T: ClassTag] extends CellExpression[T] {
  val func: CellFunction[T]
  val args: Seq[CellExpression[_]]

  override def dependencies (acc: Set[CellRef[_]]): Set[CellRef[_]] = args.foldLeft(acc)( (a,e) => e.dependencies(a))

  def eval (ctx: EvalContext): T = func.eval(ctx,args)

  override def toString: String = args.mkString(s"(${func.name} ", " ", ")")
}



//abstract class NumCall[T: ClassTag] extends CellFunctionCall[T] with NumExpression[T]

case class IntegerCall(func: CellFunction[Long], args: Seq[CellExpression[_]]) extends CellFunctionCall[Long] with IntegerExpression {
  override def evalToInteger(ctx: EvalContext): Long = func.eval(ctx, args)
  override def evalToReal(ctx: EvalContext): Double = func.eval(ctx, args).toDouble
}

case class RealCall (func: CellFunction[Double], args: Seq[CellExpression[_]]) extends CellFunctionCall[Double] with RealExpression {
  override def evalToReal(ctx: EvalContext): Double = func.eval(ctx,args)
  override def evalToInteger(ctx: EvalContext): Long = func.eval(ctx,args).toInt
}

case class BoolCall (func: CellFunction[Boolean], args: Seq[CellExpression[_]]) extends CellFunctionCall[Boolean] with BoolExpression

case class StringCall (func: CellFunction[String], args: Seq[CellExpression[_]]) extends CellFunctionCall[String] with StringExpression

case class IntegerListCall (func: CellFunction[IntegerList], args: Seq[CellExpression[_]]) extends CellFunctionCall[IntegerList] with IntegerListExpression

case class RealListCall (func: CellFunction[RealList], args: Seq[CellExpression[_]]) extends CellFunctionCall[RealList] with RealListExpression
