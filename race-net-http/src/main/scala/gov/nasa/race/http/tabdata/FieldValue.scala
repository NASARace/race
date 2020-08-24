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

import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime


/**
  * root type for field values
  *
  * FieldValue objects are invariant
  */
sealed trait FieldValue extends JsonSerializable {

  def toLong: Long
  def toDouble: Double
  def toJson: String
  def isDefined: Boolean = true
  def date: DateTime

  def serializeTo (w: JsonWriter): Unit

  // note those should automatically promote to DoubleValue if one of the operands is
  def + (term: FieldValue)(implicit date: DateTime): FieldValue
  def - (term: FieldValue)(implicit date: DateTime): FieldValue
  def * (factor: FieldValue)(implicit date: DateTime): FieldValue
  def / (factor: FieldValue)(implicit date: DateTime): FieldValue

  def neg(implicit date: DateTime): FieldValue
  def sgn: Int

  def < (fv: FieldValue): Boolean
  def <= (fv: FieldValue): Boolean
  def > (fv: FieldValue): Boolean
  def >= (fv: FieldValue): Boolean
}

/**
  * a FieldValue for which we record at which time it was created
  *
  * DatedFieldValue containers are state-based CRDTs - the FieldValue with the latest change date wins.
  * If the date is equal the conflict is resolved in terms of the containing ProviderData and change site
  */
sealed abstract class DatedFieldValue (val date: DateTime) extends FieldValue

/**
  * a scalar FieldValue that holds a Long value
  */
case class LongValue (value: Long)(implicit date: DateTime) extends DatedFieldValue(date) {
  def toLong: Long = value
  def toDouble: Double = value.toDouble
  def toJson = value.toString

  override def toString: String = s"LongValue(value:$value,date:$date)"

  def neg(implicit date: DateTime) = LongValue(-value)
  def sgn: Int = if (value < 0) -1 else if (value > 0) 1 else 0

  def serializeTo (w: JsonWriter): Unit = {
    w.beginObject
    w.writeLongMember("value", value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }

  //--- arithmetic ops
  def + (term: FieldValue)(implicit date: DateTime): FieldValue = {
    term match {
      case LongValue(n) => LongValue(value + n)
      case DoubleValue(d) => DoubleValue(value.toDouble + d)
      case UndefinedValue => this
    }
  }
  def - (term: FieldValue)(implicit date: DateTime): FieldValue = {
    term match {
      case LongValue(n) => LongValue(value - n)
      case DoubleValue(d) => DoubleValue(value.toDouble - d)
      case UndefinedValue => this
    }
  }
  def * (factor: FieldValue)(implicit date: DateTime): FieldValue = {
    factor match {
      case LongValue(n) => LongValue(value * n)
      case DoubleValue(d) => DoubleValue(value.toDouble * d)
      case UndefinedValue => LongValue(0)
    }
  }
  def / (factor: FieldValue)(implicit date: DateTime): FieldValue = {
    factor match {
      case LongValue(n) =>
        if (n == 0) {
          if (value < 0) DoubleValue(Double.NegativeInfinity)
          else if (value > 0) DoubleValue(Double.PositiveInfinity)
          else DoubleValue(Double.NaN)
        } else {
          LongValue(value / n)  // TODO - should we promote to Double here?
        }
      case _ =>
        val d = factor.toDouble
        if (d == 0) {
          if (value < 0) DoubleValue(Double.NegativeInfinity)
          else if (value > 0) DoubleValue(Double.PositiveInfinity)
          else DoubleValue(Double.NaN)
        } else {
          DoubleValue(value / d)
        }
    }
  }

  def < (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => value < n
      case DoubleValue(d) => value.toDouble < d
      case UndefinedValue => value < 0
    }
  }
  def <= (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => value <= n
      case DoubleValue(d) => value.toDouble <= d
      case UndefinedValue => value <= 0
    }
  }
  def > (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => value > n
      case DoubleValue(d) => value.toDouble > d
      case UndefinedValue => value > 0
    }
  }
  def >= (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => value >= n
      case DoubleValue(d) => value.toDouble >= d
      case UndefinedValue => value >= 0
    }
  }
}

/**
  * a scalar FieldValue that holds a Double value
  */
case class DoubleValue (value: Double)(implicit date: DateTime) extends DatedFieldValue(date) {
  def toLong: Long = Math.round(value)
  def toDouble: Double = value
  def toJson = value.toString

  override def toString: String = s"DoubleValue(value:$value,date:$date)"

  def serializeTo (w: JsonWriter): Unit = {
    w.beginObject
    w.writeDoubleMember("value", value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }

  def neg(implicit date: DateTime) = DoubleValue(-value)
  def sgn: Int = if (value < 0) -1 else if (value > 0) 1 else 0

  //--- arithmetic ops
  def + (term: FieldValue)(implicit date: DateTime): FieldValue = DoubleValue(value + term.toDouble)
  def - (term: FieldValue)(implicit date: DateTime): FieldValue = DoubleValue(value - term.toDouble)
  def * (factor: FieldValue)(implicit date: DateTime): FieldValue = DoubleValue(value * factor.toDouble)

  def / (factor: FieldValue)(implicit date: DateTime): FieldValue = {
    val d = factor.toDouble
    if (d == 0) {
      if (value < 0) DoubleValue(Double.NegativeInfinity)
      else if (value > 0) DoubleValue(Double.PositiveInfinity)
      else DoubleValue(Double.NaN)
    } else {
      DoubleValue(value / d)
    }
  }

  def < (fv: FieldValue): Boolean = value < fv.toDouble
  def <= (fv: FieldValue): Boolean = value <= fv.toDouble
  def > (fv: FieldValue): Boolean = value > fv.toDouble
  def >= (fv: FieldValue): Boolean = value >= fv.toDouble
}

/**
  * not sure this pseudo value has the right semantics if we just assume '0' - depends on the context
  */
case object UndefinedValue extends FieldValue {
  override val date = DateTime.UndefinedDateTime

  def toLong: Long = 0
  def toDouble: Double = 0.0
  def toJson = "undefined"
  override def isDefined: Boolean = false

  def serializeTo (w: JsonWriter): Unit = {
    w.writeUnQuotedValue("undefined")
  }

  def neg(implicit date: DateTime) = this
  def sgn: Int = 0

  //--- arithmetic ops
  def + (term: FieldValue)(implicit date: DateTime): FieldValue = term
  def - (term: FieldValue)(implicit date: DateTime): FieldValue = term.neg
  def * (factor: FieldValue)(implicit date: DateTime): FieldValue = this

  def / (factor: FieldValue)(implicit date: DateTime): FieldValue = {
    factor.sgn match {
      case -1 => DoubleValue(Double.NegativeInfinity)
      case 0 => DoubleValue(Double.NaN)
      case 1 => DoubleValue(Double.PositiveInfinity)
    }
  }

  def < (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => 0 < n
      case DoubleValue(d) => 0 < d
      case UndefinedValue => false
    }
  }
  def <= (fv: FieldValue): Boolean  = {
    fv match {
      case LongValue(n) => 0 <= n
      case DoubleValue(d) => 0 <= d
      case UndefinedValue => true
    }
  }
  def > (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => 0 > n
      case DoubleValue(d) => 0 > d
      case UndefinedValue => false
    }
  }
  def >= (fv: FieldValue): Boolean = {
    fv match {
      case LongValue(n) => 0 >= n
      case DoubleValue(d) => 0 >= d
      case UndefinedValue => true
    }
  }
}