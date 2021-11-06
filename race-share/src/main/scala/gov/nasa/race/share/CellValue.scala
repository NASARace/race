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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.JsonPullParser.{ArrayStart, ObjectStart, QuotedValue, UnQuotedValue}
import gov.nasa.race.common.{ByteSlice, JsonPullParser, JsonSerializable, JsonWriter, Utf8Slice}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ClassTag, classTag}


object CellValue extends JsonConstants {

   /*
    * cell values can either be specified as {"value": .. "date": ..} objects or as simple quoted/unquoted values that
    * use the provided default date
    */

  def readScalarCellValue[T,U <: CellValue[T]](p: JsonPullParser, defaultDate: DateTime, undefinedValue: T, getValue: JsonPullParser=>T, createCellValue: (T,DateTime)=>U) : U = {
    p.getLastResult match {
      case JsonPullParser.ObjectStart =>
        var v: T = undefinedValue
        var date: DateTime = defaultDate

        p.foreachMemberInCurrentObject {
          case VALUE => v = getValue( p)
          case DATE => date = p.dateTimeValue
        }

        createCellValue(v,date)

      case JsonPullParser.UnQuotedValue | JsonPullParser.QuotedValue =>
        val v = getValue( p)
        createCellValue(v,defaultDate)

      case _ => throw p.exception("expected scalar value or value object")
    }
  }

  def readArrayCellValue[E,T <: ListCellValue[_]](p: JsonPullParser, defaultDate: DateTime, undefinedValue: Array[E],
                                                  readElements: =>Array[E], createCellValue: (Array[E],DateTime)=>T) : T = {
    p.getLastResult match {
      case JsonPullParser.ObjectStart =>
        var v = undefinedValue
        var date = defaultDate

        p.foreachMemberInCurrentObject {
          case VALUE => v = readElements
          case DATE => date = p.dateTimeValue
        }

        createCellValue(v,date)

      case JsonPullParser.ArrayStart =>
        val v = readElements
        createCellValue(v,defaultDate)

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

  def copyWithDate (newDate: DateTime): CellValue[T]

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
  val undefinedValue: Long = 0
  def readCellValue(p: JsonPullParser, date: DateTime) = readScalarCellValue(p,date,undefinedValue,_.unQuotedValue.toLong,apply)
}
case class IntegerCellValue(value: Long, date: DateTime) extends NumCellValue[Long] {
  override def undefinedCellValue = UndefinedIntegerCellValue
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeLongMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)
}
object UndefinedIntegerCellValue extends IntegerCellValue(IntegerCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[Long]


object RealCellValue {
  val undefinedValue: Double = 0.0
  def readCellValue(p: JsonPullParser, date: DateTime) = readScalarCellValue(p,date,undefinedValue, _.unQuotedValue.toDouble,apply)
}
case class RealCellValue(value: Double, date: DateTime) extends NumCellValue[Double] {
  override def undefinedCellValue = UndefinedRealCellValue
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeDoubleMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)

}
object UndefinedRealCellValue extends RealCellValue(RealCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[Double]


object BoolCellValue {
  val undefinedValue: Boolean = false
  def readCellValue(p: JsonPullParser, date: DateTime) = readScalarCellValue(p,date,undefinedValue,_.unQuotedValue.toBoolean,apply)
}
case class BoolCellValue (value: Boolean, date: DateTime) extends CellValue[Boolean] {
  override def valueToString: String = value.toString
  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeBooleanMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
  override def undefinedCellValue = UndefinedBoolCellValue
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)
}
object UndefinedBoolCellValue extends BoolCellValue(BoolCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[Boolean]

//--- string based cell values

abstract class StringBasedCellValue extends CellValue[String] {
  override def valueToString: String = "\"" + value + '"'

  override def serializeTo (w: JsonWriter): Unit = w.writeObject( w=> {
    w.writeStringMember("value",value)
    w.writeLongMember("date",date.toEpochMillis)
  })
}

object StringCellValue {
  def undefinedValue: String = ""
  def readCellValue (p: JsonPullParser, date: DateTime) = readScalarCellValue(p,date,undefinedValue,_.quotedValue.toString,apply)
}
case class StringCellValue (value: String, date: DateTime) extends StringBasedCellValue {
  override def undefinedCellValue = UndefinedStringCellValue
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)
}
object UndefinedStringCellValue extends StringCellValue(StringCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[String]


object LinkCellValue {
  def undefinedValue: String = ""
  def readCellValue (p: JsonPullParser, date: DateTime) = readScalarCellValue(p,date,undefinedValue,_.quotedValue.toString,apply)
}
case class LinkCellValue (value: String, date: DateTime) extends StringBasedCellValue {
  override def undefinedCellValue = UndefinedLinkCellValue
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)
}
object UndefinedLinkCellValue extends LinkCellValue(LinkCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[String]

//--- list CVs

object IntegerListCellValue {
  def undefinedValue = Array.empty[Long]
  def readCellValue (p: JsonPullParser, date: DateTime) = readArrayCellValue( p, date, undefinedValue,
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
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)
}
object UndefinedIntegerListCellValue extends IntegerListCellValue( IntegerListCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[IntegerList]

object RealListCellValue {
  def undefinedValue = Array.empty[Double]
  def readCellValue (p: JsonPullParser, date: DateTime) = readArrayCellValue( p, date, undefinedValue,
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
  override def copyWithDate (newDate: DateTime) = copy(date = newDate)
}
object UndefinedRealListCellValue extends RealListCellValue(RealListCellValue.undefinedValue, DateTime.UndefinedDateTime) with UndefinedCellValue[RealList]

