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
package gov.nasa.race.http.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.JsonPullParser.{ArrayStart, ObjectStart, QuotedValue, UnQuotedValue}
import gov.nasa.race.common.{ByteSlice, JsonPullParser, JsonSerializable, JsonWriter, Utf8Slice}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ClassTag, classTag}


object CellValue {
  val _value_ = asc("value")
  val _date_ = asc("date")

   /*
    * cell values can either be specified as {"value": .. "date": ..} objects or as simple quoted/unquoted values that
    * use the provided default date
    */

  def parseUnQuotedValueFrom[T,U <: CellValue[T]] (p: JsonPullParser, date: DateTime, toValue: Utf8Slice=>T, createCellValue: (T,DateTime)=>U) : U = {
    p.getLastResult match {
      case JsonPullParser.ObjectStart =>
        val v = toValue(p.readUnQuotedMember(_value_))
        val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
        p.skipPastAggregate()
        createCellValue(v,d)

      case JsonPullParser.UnQuotedValue =>
        val v = toValue(p.value)
        createCellValue(v,date)

      case _ => throw p.exception("expected unquoted value or value object")
    }
  }

  def parseQuotedValueFrom[T,U <: CellValue[T]] (p: JsonPullParser, date: DateTime, toValue: Utf8Slice=>T, createCellValue: (T,DateTime)=>U) : U = {
    p.getLastResult match {
      case JsonPullParser.ObjectStart =>
        val v = toValue(p.readQuotedMember(_value_))
        val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
        p.skipPastAggregate()
        createCellValue(v,d)

      case JsonPullParser.QuotedValue =>
        val v = toValue(p.value)
        createCellValue(v,date)

      case _ => throw p.exception("expected quoted value or value object")
    }
  }

  def parseArrayValueFrom[E,T <: ListCellValue[_]] (p: JsonPullParser, defaultDate: DateTime,
                                                readElements: =>Array[E], createCellValue: (Array[E],DateTime)=>T) : T = {
    p.getLastResult match {
      case JsonPullParser.ObjectStart =>
        p.readArrayMemberName(_value_)
        val es = readElements
        val d = p.readOptionalDateTimeMember(_date_).getOrElse(defaultDate)
        p.skipPastAggregate()
        createCellValue(es,d)

      case JsonPullParser.ArrayStart =>
        val es = readElements
        createCellValue(es,defaultDate)

      case _ => throw p.exception("expected array value or value object")
    }
  }
}
import CellValue._


/**
  * invariant content of cells, consisting of a generic value and associated date (which is used for conflict resolution)
  *
  * note this has to be an abstract class since Scala 2 does not support bounds on trait type parameters
  */
abstract class CellValue[T: ClassTag] {
  val value: T
  val date: DateTime

  def isDefined: Boolean = true
  def cellType: Class[_] = classTag[T].runtimeClass

  def valueToString: String = value.toString
  def toJson: String = s"""{"value:"$valueToString,"date:"${date.toEpochMillis}}"""
  def serializeTo (w: JsonWriter): Unit

  def undefinedCellValue: UndefinedCellValue[T]
}

/**
  * mixin for undefined CellValue functions
  */
trait UndefinedCellValue[T] extends CellValue[T] {
  self: CellValue[T] =>
  override def isDefined: Boolean = false

  override def valueToString: String = ""

  override def toJson = "undefined"
  override def serializeTo (w: JsonWriter): Unit = w.writeUnQuotedValue(toJson)
}

/**
  * a CellValue that holds numeric values
  * note that we don't lift numeric operations since accessing the readonly value from the outside is perfectly fine
  */
abstract class NumCellValue[T: ClassTag](implicit num: math.Numeric[T]) extends CellValue[T] {
  def toReal: Double = num.toDouble(value)
  def toInteger: Long = num.toLong(value)

  override def toJson: String = valueToString
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeUnQuotedMember("value",valueToString)
    w.writeLongMember("date",date.toEpochMillis)
  })
}

/**
  * value type root for CellValues holding lists
  * note that we can't directly use arrays since we need to be able to type match on the value
  */
abstract class CVList[E: ClassTag] {
  type This <: CVList[E]
  val elements: Array[E]

  def elementType: Class[_] = classTag[E].runtimeClass
  def copyWithElements (es: Array[E]): This

  // forwarded methods (hopefully inlined by the VM)
  def apply(i: Int): E = elements(i)
  def length: Int = elements.length

  def foreach (f:E=>Unit): Unit = elements.foreach(f)
  def foldLeft[B](z:B)(f: (B,E)=>B): B = elements.foldLeft(z)(f)
  def foldRight[B](z:B)(f: (E,B)=>B): B = elements.foldRight(z)(f)

  def append(v: E): This = copyWithElements(elements.appended(v))

  def drop (n: Int): This = copyWithElements(elements.drop(n))
  def dropRight (n: Int): This = copyWithElements(elements.dropRight(n))

  def appendBounded(v: E, maxLength: Int): This = {
    if (elements.length < maxLength) {
      copyWithElements(elements.appended(v))
    } else {
      val es = elements.clone()
      System.arraycopy(es, 1, es, 0, es.length-1)
      es(length-1) = v
      copyWithElements(es)
    }
  }

  def prependBounded(v: E, maxLength: Int): This = {
    if (elements.length < maxLength) {
      copyWithElements(elements.prepended(v))
    } else {
      val es = elements.clone()
      System.arraycopy(es, 0, es, 1, es.length-1)
      es(0) = v
      copyWithElements(es)
    }
  }

  def sameElements (es: collection.IterableOnce[E]): Boolean = elements.sameElements(es)

  def valueToString: String = elements.mkString("[",",","]")
  override def toString: String = valueToString


  //... and more to follow
}

/**
  * a CellValue list type for numeric elements
  */
abstract class NumCVList[E: ClassTag](implicit num: math.Numeric[E]) extends CVList[E] {
  def sum: E = elements.sum
  def avg: Double = num.toDouble(sum) / (elements.length)
  def min: E = elements.min
  def max: E = elements.max

  // generic versions - override for matching concrete element types
  def toIntegerArray: Array[Long] = elements.map(num.toLong)
  def toRealArray: Array[Double] = elements.map(num.toDouble)

  //... and more to follow
}

//--- concrete CVList types (*not* CellValues) - we need to be able to pattern match on them

object IntegerList {
  def apply (vs: Long*): IntegerList = new IntegerList(Array[Long](vs:_*))
}
case class IntegerList (elements: Array[Long]) extends NumCVList[Long] {
  type This = IntegerList
  override def copyWithElements (es: Array[Long]): This = IntegerList(es)

}

object RealList {
  def apply (vs: Double*): RealList = new RealList(Array[Double](vs:_*))
}
case class RealList (elements: Array[Double]) extends NumCVList[Double] {
  type This = RealList
  override def copyWithElements (es: Array[Double]): This = RealList(es)
}

abstract class ListCellValue[T <: CVList[_] : ClassTag] extends CellValue[T] {

}

//--- concrete CellValue types (they always come in pairs of a CV case class and an object for the respective undefined value

//--- scalar CVs

object IntegerCellValue {
  def parseValueFrom (p: JsonPullParser, defaultDate: DateTime) = parseUnQuotedValueFrom(p,defaultDate,_.toLong,apply)
}
case class IntegerCellValue(value: Long, date: DateTime) extends NumCellValue[Long] {
  override def undefinedCellValue = UndefinedIntegerCellValue
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeLongMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
}
object UndefinedIntegerCellValue extends IntegerCellValue(0, DateTime.UndefinedDateTime) with UndefinedCellValue[Long]


object RealCellValue {
  def parseValueFrom (p: JsonPullParser, date: DateTime) = parseUnQuotedValueFrom(p,date,_.toDouble,apply)
}
case class RealCellValue(value: Double, date: DateTime) extends NumCellValue[Double] {
  override def undefinedCellValue = UndefinedRealCellValue
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeDoubleMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
}
object UndefinedRealCellValue extends RealCellValue(0.0, DateTime.UndefinedDateTime) with UndefinedCellValue[Double]


object BoolCellValue {
  def parseValueFrom (p: JsonPullParser, date: DateTime) = parseUnQuotedValueFrom(p,date,_.toBoolean,apply)
}
case class BoolCellValue (value: Boolean, date: DateTime) extends CellValue[Boolean] {
  override def valueToString: String = value.toString
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeBooleanMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
  override def undefinedCellValue = UndefinedBoolCellValue

}
object UndefinedBoolCellValue extends BoolCellValue(false, DateTime.UndefinedDateTime) with UndefinedCellValue[Boolean]


object StringCellValue {
  def parseValueFrom (p: JsonPullParser, date: DateTime) = parseQuotedValueFrom(p,date,_.toString,apply)
}
case class StringCellValue (value: String, date: DateTime) extends CellValue[String] {
  override def valueToString: String = "\"" + value + '"'
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeStringMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
  override def undefinedCellValue = UndefinedStringCellValue
}
object UndefinedStringCellValue extends StringCellValue("", DateTime.UndefinedDateTime) with UndefinedCellValue[String]


//--- list CVs

object IntegerListCellValue {
  def parseValueFrom (p: JsonPullParser, date: DateTime) = parseArrayValueFrom(p,date,
    p.readCurrentLongArrayInto(ArrayBuffer.empty[Long]).toArray,
    (es: Array[Long],d: DateTime) => IntegerListCellValue( IntegerList(es),d)
  )
}
case class IntegerListCellValue (value: IntegerList, date: DateTime) extends ListCellValue[IntegerList] {
  def this (es: Array[Long], date: DateTime) = this(IntegerList(es),date)
  override def undefinedCellValue = UndefinedIntegerListCellValue
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeLongArrayMember("value",value.elements)
    w.writeLongMember("date",date.toEpochMillis)
  })
}
object UndefinedIntegerListCellValue extends IntegerListCellValue(Array.empty[Long], DateTime.UndefinedDateTime) with UndefinedCellValue[IntegerList]

object RealListCellValue {
  def parseValueFrom (p: JsonPullParser, date: DateTime) = parseArrayValueFrom(p,date,
    p.readCurrentDoubleArrayInto(ArrayBuffer.empty[Double]).toArray,
    (es: Array[Double],d: DateTime) => RealListCellValue( RealList(es),d)
  )
}
case class RealListCellValue (value: RealList, date: DateTime) extends ListCellValue[RealList] {
  def this (es: Array[Double], date: DateTime) = this(RealList(es),date)
  override def undefinedCellValue = UndefinedRealListCellValue
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeDoubleArrayMember("value",value.elements)
    w.writeLongMember("date",date.toEpochMillis)
  })
}
object UndefinedRealListCellValue extends RealListCellValue(Array.empty[Double], DateTime.UndefinedDateTime) with UndefinedCellValue[RealList]




/*
/**
  * root type for cell values
  *
  * Cell objects are invariant
  */
sealed abstract class CellValue(val date: DateTime) extends JsonSerializable {
  type This

  def valueToString: String
  def toJson: String
  def isDefined: Boolean = true

  def serializeTo (w: JsonWriter): Unit

  def undefinedCellValue: UndefinedCellValue

  def valueEquals (other: CellValue): Boolean
}

trait CellTyped {
  val cellType: Class[_]
}

/**
  * root type for a cell that is not defined
  */
trait UndefinedCellValue extends CellValue {
  override def valueToString: String = ""
  override def toJson = "undefined"
  override def isDefined: Boolean = false
  override def serializeTo (w: JsonWriter): Unit = w.writeUnQuotedValue("undefined")
}

/**
  * a cell value that supports numeric operations
  */
trait NumCellValue extends CellValue {
  type This <: NumCellValue

  def toLong: Long
  def toDouble: Double

  // TODO - should this preserve the this.type?
  def +(term: NumCellValue)(implicit date: DateTime): NumCellValue
  def -(term: NumCellValue)(implicit date: DateTime): NumCellValue
  def *(factor: NumCellValue)(implicit date: DateTime): NumCellValue
  def /(factor: NumCellValue)(implicit date: DateTime): NumCellValue

  def neg(implicit date: DateTime): This
  def sgn: Int

  def < (fv: NumCellValue): Boolean
  def <= (fv: NumCellValue): Boolean
  def > (fv: NumCellValue): Boolean
  def >= (fv: NumCellValue): Boolean
}

/**
  * undefined numeric cell value
  * TODO - not sure about the comparison operators
  */
trait UndefinedNumCellValue extends  UndefinedCellValue with NumCellValue {

  //--- arithmetic ops
  override def *(factor: NumCellValue)(implicit date: DateTime): NumCellValue = this

  override def /(factor: NumCellValue)(implicit date: DateTime): NumCellValue = {
    factor.sgn match {
      case -1 => DoubleCellValue(Double.NegativeInfinity)
      case 0 => DoubleCellValue(Double.NaN)
      case 1 => DoubleCellValue(Double.PositiveInfinity)
    }
  }

  override def < (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => 0 < n
      case DoubleCellValue(d) => 0 < d
    }
  }
  override def <= (fv: NumCellValue): Boolean  = {
    fv match {
      case LongCellValue(n) => 0 <= n
      case DoubleCellValue(d) => 0 <= d
    }
  }
  override def > (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => 0 > n
      case DoubleCellValue(d) => 0 > d
    }
  }
  override def >= (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => 0 >= n
      case DoubleCellValue(d) => 0 >= d
    }
  }
}

object LongCellValue {
  val cellType = classOf[LongCellValue]

  // we already parsed the row-id and the parser is at the start of a {"value"..} object or an un-quoted value
  def parseFrom (p: JsonPullParser)(implicit date: DateTime): LongCellValue = p.getLastResult match {
    case ObjectStart =>
      val v = p.readUnQuotedMember(_value_).toLong
      val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
      p.skipPastAggregate()
      LongCellValue(v)(d)

    case UnQuotedValue =>
      val v = p.value.toLong
      LongCellValue(v)

    case _ => throw p.exception("expected integer value or value object")
  }
}

/**
  * a scalar Cell that holds a Long value
  */
case class LongCellValue(value: Long)(implicit date: DateTime) extends CellValue(date) with NumCellValue {
  type This = LongCellValue

  def toLong: Long = value
  def toDouble: Double = value.toDouble
  def toJson = value.toString
  def valueToString = value.toString

  def valueEquals (other: CellValue): Boolean = {
    other.isInstanceOf[LongCellValue] && other.asInstanceOf[LongCellValue].value == value
  }

  override def undefinedCellValue: UndefinedCellValue = UndefinedLongCellValue
  override def toString: String = s"LongValue(value:$value,date:$date)"

  def neg(implicit date: DateTime) = LongCellValue(-value)
  def sgn: Int = if (value < 0) -1 else if (value > 0) 1 else 0

  def serializeTo (w: JsonWriter): Unit = {
    w.beginObject
    w.writeLongMember("value", value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }

  //--- arithmetic ops
  def +(term: NumCellValue)(implicit date: DateTime): NumCellValue = {
    term match {
      case LongCellValue(n) => LongCellValue(value + n)
      case DoubleCellValue(d) => DoubleCellValue(value.toDouble + d)
    }
  }
  def -(term: NumCellValue)(implicit date: DateTime): NumCellValue = {
    term match {
      case LongCellValue(n) => LongCellValue(value - n)
      case DoubleCellValue(d) => DoubleCellValue(value.toDouble - d)
    }
  }
  def *(factor: NumCellValue)(implicit date: DateTime): NumCellValue = {
    factor match {
      case LongCellValue(n) => LongCellValue(value * n)
      case DoubleCellValue(d) => DoubleCellValue(value.toDouble * d)
    }
  }
  def /(factor: NumCellValue)(implicit date: DateTime): NumCellValue = {
    factor match {
      case LongCellValue(n) =>
        if (n == 0) {
          if (value < 0) DoubleCellValue(Double.NegativeInfinity)
          else if (value > 0) DoubleCellValue(Double.PositiveInfinity)
          else DoubleCellValue(Double.NaN)
        } else {
          LongCellValue(value / n)  // TODO - should we promote to Double here?
        }
      case _ =>
        val d = factor.toDouble
        if (d == 0) {
          if (value < 0) DoubleCellValue(Double.NegativeInfinity)
          else if (value > 0) DoubleCellValue(Double.PositiveInfinity)
          else DoubleCellValue(Double.NaN)
        } else {
          DoubleCellValue(value / d)
        }
    }
  }

  def < (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => value < n
      case DoubleCellValue(d) => value.toDouble < d
    }
  }
  def <= (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => value <= n
      case DoubleCellValue(d) => value.toDouble <= d
    }
  }
  def > (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => value > n
      case DoubleCellValue(d) => value.toDouble > d
    }
  }
  def >= (fv: NumCellValue): Boolean = {
    fv match {
      case LongCellValue(n) => value >= n
      case DoubleCellValue(d) => value.toDouble >= d
    }
  }
}

object UndefinedLongCellValue extends LongCellValue(0)(DateTime.Date0) with UndefinedNumCellValue {
  override def neg(implicit date: DateTime) = this
}

object DoubleCellValue {
  val cellType = classOf[DoubleCellValue]

  def parseFrom (p: JsonPullParser)(implicit date: DateTime): DoubleCellValue = p.getLastResult match {
    case ObjectStart =>
      val v = p.readUnQuotedMember(_value_).toDouble
      val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
      p.skipPastAggregate()
      DoubleCellValue(v)(d)

    case UnQuotedValue =>
      val v = p.value.toDouble
      DoubleCellValue(v)

    case _ => throw p.exception("expected rational value or value object")
  }
}

/**
  * a scalar cell that holds a Double value
  */
case class DoubleCellValue(value: Double)(implicit date: DateTime) extends CellValue(date) with NumCellValue {
  type This = DoubleCellValue

  def toLong: Long = Math.round(value)
  def toDouble: Double = value
  def toJson = value.toString
  def valueToString = value.toString

  def valueEquals (other: CellValue): Boolean = {
    other.isInstanceOf[DoubleCellValue] && other.asInstanceOf[DoubleCellValue].value == value
  }

  override def undefinedCellValue: UndefinedCellValue = UndefinedDoubleCellValue
  override def toString: String = s"DoubleValue(value:$value,date:$date)"

  def serializeTo (w: JsonWriter): Unit = {
    w.beginObject
    w.writeDoubleMember("value", value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }

  def neg(implicit date: DateTime) = DoubleCellValue(-value)
  def sgn: Int = if (value < 0) -1 else if (value > 0) 1 else 0

  //--- arithmetic ops
  def +(term: NumCellValue)(implicit date: DateTime): NumCellValue = DoubleCellValue(value + term.toDouble)
  def -(term: NumCellValue)(implicit date: DateTime): NumCellValue = DoubleCellValue(value - term.toDouble)
  def *(factor: NumCellValue)(implicit date: DateTime): NumCellValue = DoubleCellValue(value * factor.toDouble)

  def /(factor: NumCellValue)(implicit date: DateTime): NumCellValue = {
    val d = factor.toDouble
    if (d == 0) {
      if (value < 0) DoubleCellValue(Double.NegativeInfinity)
      else if (value > 0) DoubleCellValue(Double.PositiveInfinity)
      else DoubleCellValue(Double.NaN)
    } else {
      DoubleCellValue(value / d)
    }
  }

  def < (fv: NumCellValue): Boolean = value < fv.toDouble
  def <= (fv: NumCellValue): Boolean = value <= fv.toDouble
  def > (fv: NumCellValue): Boolean = value > fv.toDouble
  def >= (fv: NumCellValue): Boolean = value >= fv.toDouble
}

object UndefinedDoubleCellValue extends DoubleCellValue(0.0)(DateTime.Date0) with UndefinedNumCellValue {
  override def neg(implicit date: DateTime) = this
}

/**
  * scalar cell that holds a Boolean value
  */

object BooleanCellValue {
  val cellType = classOf[BooleanCellValue]

  def parseFrom (p: JsonPullParser)(implicit date: DateTime): BooleanCellValue = p.getLastResult match {
    case ObjectStart =>
      val v = p.readQuotedMember(_value_).toBoolean
      val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
      p.skipPastAggregate()
      BooleanCellValue(v)(d)

    case QuotedValue =>
      val v = p.value.toBoolean
      BooleanCellValue(v)

    case _ => throw p.exception("expected boolean value or value object")
  }
}

case class BooleanCellValue(value: Boolean)(implicit date: DateTime) extends CellValue(date) {
  override def valueToString: String = value.toString

  def valueEquals (other: CellValue): Boolean = {
    other.isInstanceOf[BooleanCellValue] && other.asInstanceOf[BooleanCellValue].value == value
  }

  override def toJson: String = if (value) "\"true\"" else "\"false\""
  override def undefinedCellValue: UndefinedCellValue = UndefinedBooleanCellValue

  override def serializeTo(w: JsonWriter): Unit = {
    w.beginObject
    w.writeBooleanMember("value", value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }

  def toBoolean: Boolean = value

  def &&(other: BooleanCellValue)(implicit date: DateTime): BooleanCellValue = BooleanCellValue(value && other.toBoolean)
  def ||(other: BooleanCellValue)(implicit date: DateTime): BooleanCellValue = BooleanCellValue(value || other.toBoolean)
  def unary_! (implicit date: DateTime): BooleanCellValue = BooleanCellValue(!value)

}

object UndefinedBooleanCellValue extends BooleanCellValue(false)(DateTime.Date0) with UndefinedCellValue

/**
  * string cell
  */

object StringCellValue {
  val cellType = classOf[StringCellValue]

  def parseFrom (p: JsonPullParser)(implicit date: DateTime): StringCellValue = p.getLastResult match {
    case ObjectStart =>
      val v = p.readQuotedMember(_value_).toString
      val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
      p.skipPastAggregate()
      StringCellValue(v)(d)

    case QuotedValue =>
      val v = p.value.toString
      StringCellValue(v)

    case _ => throw p.exception("expected string value or value object")
  }
}

case class StringCellValue (value: String)(implicit date: DateTime) extends CellValue(date){
  override def valueToString: String = value

  def valueEquals (other: CellValue): Boolean = {
    other.isInstanceOf[StringCellValue] && other.asInstanceOf[StringCellValue].value == value
  }

  override def toJson: String = "\"" + value + '"'
  override def undefinedCellValue: UndefinedCellValue = UndefinedStringCellValue

  override def serializeTo(w: JsonWriter): Unit = {
    w.beginObject
    w.writeStringMember("value", value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }
}

object UndefinedStringCellValue extends StringCellValue("")(DateTime.Date0) with UndefinedCellValue

//--- array cell values

trait ListCellValue[T] {
  type This <: ListCellValue[T]
  val value: Array[T]

  def apply(i: Int): T
  def length: Int
  def append (v: T)(implicit date: DateTime): This
  def appendBounded(v: T, maxLength: Int)(implicit date: DateTime): This
  def pushBounded(v: T, maxLength: Int)(implicit date: DateTime): This
  def foreach (f:T=>Unit): Unit = value.foreach(f)
  def foldLeft[B](z:B)(f: (B,T)=>B): B = value.foldLeft(z)(f)
  //... and more to follow
}

/**
  * Long list
  */
object LongListCellValue {
  val cellType = classOf[LongListCellValue]

  def parseFrom (p: JsonPullParser)(implicit date: DateTime): LongListCellValue = p.getLastResult match {
    case ObjectStart =>
      val v = p.readNextLongArrayMemberInto(_value_, ArrayBuffer.empty[Long]).toArray
      val d = p.readOptionalDateTimeMember(_date_).getOrElse(date)
      p.skipPastAggregate()
      LongListCellValue(v)(d)

    case ArrayStart =>
      val v = p.readCurrentLongArrayInto(ArrayBuffer.empty[Long]).toArray
      LongListCellValue(v)

    case _ => throw p.exception("expected integer array value or value object")
  }
}

case class LongListCellValue (value: Array[Long])(implicit date: DateTime) extends CellValue(date) with ListCellValue[Long] {
  type This = LongListCellValue

  override def valueToString: String = value.mkString("[",",","]")
  override def toJson: String = valueToString
  override def undefinedCellValue: UndefinedCellValue = UndefinedLongListCellValue

  override def toString: String = {
    s"LongListCellValue(value:$valueToString,date:$date)"
  }

  override def serializeTo (w: JsonWriter): Unit = {
    w.beginObject
    w.writeLongArrayMember("value",value)
    if (date.isDefined) w.writeDateTimeMember("date", date)
    w.endObject
  }

  override def apply (i: Int): Long = value(i)
  override def length: Int = value.length

  def valueEquals (o: CellValue): Boolean = {
    if (o.isInstanceOf[LongListCellValue]){
      val other = o.asInstanceOf[LongListCellValue]
      val len = value.length
      val ov = other.value
      if (len != ov.length) return false
      var i = 0
      while ((i < len) && (value(i) == ov(i))) i += 1
      (i == len)
    } else false
  }

  override def equals(o: Any): Boolean = {
    if (o.isInstanceOf[LongListCellValue]) {
      val other = o.asInstanceOf[LongListCellValue]
      (date == other.date) && valueEquals(other)
    } else false
  }

  override def append (v: Long)(implicit date: DateTime): LongListCellValue = {
    val vs = new Array[Long](value.length+1)
    System.arraycopy(value,0,vs,0,value.length)
    vs(vs.length-1) = v
    LongListCellValue(vs)(date)
  }

  override def appendBounded(v: Long, maxLength: Int)(implicit date: DateTime): LongListCellValue = {
    val len = Math.min(maxLength, value.length+1)
    val vs = new Array[Long](len)
    if (value.length >= maxLength) {
      System.arraycopy(value,value.length - maxLength+1,vs,0,maxLength-1)
    } else {
      System.arraycopy(value,0,vs,0,value.length)
    }
    vs(vs.length-1) = v
    LongListCellValue(vs)(date)
  }

  override def pushBounded(v: Long, maxLength: Int)(implicit date: DateTime): LongListCellValue = {
    val len = Math.min(maxLength, value.length+1)
    val vs = new Array[Long](len)
    if (value.length >= maxLength) {
      System.arraycopy(value,0,vs,1,maxLength-1)
    } else {
      System.arraycopy(value,0,vs,1,value.length)
    }
    vs(0) = v
    LongListCellValue(vs)(date)
  }
}

object UndefinedLongListCellValue extends LongListCellValue(Array.empty[Long])(DateTime.Date0) with UndefinedCellValue


 */