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
  * root type for field values
  *
  * Note this has to be a universal trait since we have value classes extending it
  */
sealed trait FieldValue {
  def toLong: Long
  def toDouble: Double
  def toJson: String
  def isDefined: Boolean = true

  // note those should automatically promote to DoubleValue if one of the operands is
  def + (term: FieldValue): FieldValue
  def - (term: FieldValue): FieldValue
  def * (factor: FieldValue): FieldValue
  def / (factor: FieldValue): FieldValue

  def neg: FieldValue
  def sgn: Int

  def < (fv: FieldValue): Boolean
  def <= (fv: FieldValue): Boolean
  def > (fv: FieldValue): Boolean
  def >= (fv: FieldValue): Boolean
}

case class LongValue (value: Long) extends FieldValue {
  def toLong: Long = value
  def toDouble: Double = value.toDouble
  def toJson = value.toString

  def neg = LongValue(-value)
  def sgn: Int = if (value < 0) -1 else if (value > 0) 1 else 0

  //--- arithmetic ops
  def + (term: FieldValue): FieldValue = {
    term match {
      case LongValue(n) => LongValue(value + n)
      case DoubleValue(d) => DoubleValue(value.toDouble + d)
      case UndefinedValue => this
    }
  }
  def - (term: FieldValue): FieldValue = {
    term match {
      case LongValue(n) => LongValue(value - n)
      case DoubleValue(d) => DoubleValue(value.toDouble - d)
      case UndefinedValue => this
    }
  }
  def * (factor: FieldValue): FieldValue = {
    factor match {
      case LongValue(n) => LongValue(value * n)
      case DoubleValue(d) => DoubleValue(value.toDouble * d)
      case UndefinedValue => LongValue(0)
    }
  }
  def / (factor: FieldValue): FieldValue = {
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

case class DoubleValue (value: Double) extends FieldValue {
  def toLong: Long = Math.round(value)
  def toDouble: Double = value
  def toJson = value.toString

  def neg = DoubleValue(-value)
  def sgn: Int = if (value < 0) -1 else if (value > 0) 1 else 0

  //--- arithmetic ops
  def + (term: FieldValue): FieldValue = DoubleValue(value + term.toDouble)
  def - (term: FieldValue): FieldValue = DoubleValue(value - term.toDouble)
  def * (factor: FieldValue): FieldValue = DoubleValue(value * factor.toDouble)

  def / (factor: FieldValue): FieldValue = {
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
  def toLong: Long = 0
  def toDouble: Double = 0.0
  def toJson = "undefined"
  override def isDefined: Boolean = false

  def neg = this
  def sgn: Int = 0

  //--- arithmetic ops
  def + (term: FieldValue): FieldValue = term
  def - (term: FieldValue): FieldValue = term.neg
  def * (factor: FieldValue): FieldValue = this

  def / (factor: FieldValue): FieldValue = {
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