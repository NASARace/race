/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.common

object JsConverter {
  implicit object IntJsConverter extends  PassThroughJsConverter[Int]
  implicit object DoubleJsConverter extends PassThroughJsConverter[Double]
  implicit object BooleanJsConverter extends PassThroughJsConverter[Boolean]

  implicit object StringJsConverter extends JsConverter[String] {
    def appendJs( sb: StringBuilder, o: String): Unit = {
      sb.append('\'')
      sb.append(o)
      sb.append('\'')
    }
  }
}

trait JsConverter[T] {
  def appendJs( sb: StringBuilder, o: T): Unit
}

trait PassThroughJsConverter[T] extends JsConverter[T] {
  def appendJs(sb: StringBuilder, o: T): Unit = sb.append(o)
}

/**
 * something that can produce a JavaScript string
 */
trait JsConvertible {

  def appendJs(sb: StringBuilder): Unit

  def appendJsObject (sb: StringBuilder): Unit = {
    sb.append('{')
    appendJs(sb)
    sb.append('}')
  }

  def toJsObject: String = {
    val sb = new StringBuilder()
    appendJsObject(sb)
    sb.toString
  }

  def toJs: String = {
    val sb = new StringBuilder()
    appendJs(sb)
    sb.toString
  }

  def appendJsString(sb: StringBuilder, s: String): Unit = {
    sb.append('\'')
    sb.append(s)
    sb.append('\'')
  }

  def appendJsStringMember(sb: StringBuilder, propertyName: String, value: String): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendJsString(sb, value)
  }
  def appendOptionalJsStringMember(sb: StringBuilder, propertyName: String, maybeValue: Option[String]): Unit = {
    if (maybeValue.isDefined) appendJsStringMember(sb,propertyName,maybeValue.get)
  }

  def appendJsIntMember(sb: StringBuilder, propertyName: String, value: Int): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    sb.append(value)
  }
  def appendOptionalJsIntMember(sb: StringBuilder, propertyName: String, maybeValue: Option[Int]): Unit = {
    if (maybeValue.isDefined) appendJsIntMember(sb,propertyName,maybeValue.get)
  }

  def appendJsDoubleMember(sb: StringBuilder, propertyName: String, value: Double): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    sb.append(value)
  }
  def appendOptionalJsDoubleMember(sb: StringBuilder, propertyName: String, maybeValue: Option[Double]): Unit = {
    if (maybeValue.isDefined) appendJsDoubleMember(sb,propertyName,maybeValue.get)
  }

  def appendJsBooleanMember(sb: StringBuilder, propertyName: String, value: Boolean): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    sb.append(value)
  }
  def appendOptionalJsBooleanMember(sb: StringBuilder, propertyName: String, maybeValue: Option[Boolean]): Unit = {
    if (maybeValue.isDefined) appendJsBooleanMember(sb,propertyName,maybeValue.get)
  }

  def appendJsObject[T<:AnyRef](sb: StringBuilder, o: T)(implicit conv: JsConverter[T]): Unit = {
    if (o != null) {
      sb.append('{')
      conv.appendJs(sb,o)
      sb.append('}')
    } else {
      sb.append("null")
    }
  }

  def appendJsObject(sb: StringBuilder, o: JsConvertible): Unit = {
    if (o != null) {
      sb.append('{')
      o.appendJs(sb)
      sb.append('}')
    } else {
      sb.append("null")
    }
  }

  def appendJsMember(sb: StringBuilder, propertyName: String)(f: StringBuilder=>Unit): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    f(sb)
  }

  def appendJsObjectMember(sb: StringBuilder, propertyName: String, o: JsConvertible): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendJsObject(sb,o)
  }
  def appendOptionalJsObjectMember(sb: StringBuilder, propertyName: String, maybeValue: Option[JsConvertible]): Unit = {
    if (maybeValue.isDefined) appendJsObjectMember(sb,propertyName,maybeValue.get)
  }

  def appendJsArray[T](sb: StringBuilder, o: IterableOnce[T])(implicit conv: JsConverter[T]): Unit = {
    val it = o.iterator
    sb.append('[')
    if (it.nonEmpty) {
      it.foreach { e =>
        conv.appendJs(sb, e)
        sb.append(',')
      }
      sb.setLength(sb.length() - 1) // remove last comma
    }
    sb.append(']')
  }

  def appendJsArray(sb: StringBuilder, o: IterableOnce[JsConvertible]): Unit = {
    val it = o.iterator
    sb.append('[')
    if (it.nonEmpty) {
      it.foreach { o =>
        o.appendJsObject(sb)
        sb.append(',')
      }
      sb.setLength(sb.length() - 1) // remove last comma
    }
    sb.append(']')
  }

  def appendJsArrayMember[T](sb: StringBuilder, propertyName: String, o: IterableOnce[T])(implicit conv: JsConverter[T]): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendJsArray(sb,o)
  }

  def appendJsArrayMember(sb: StringBuilder, propertyName: String, o: IterableOnce[JsConvertible]): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendJsArray(sb,o)
  }
  def appendOptionalJsArrayMember(sb: StringBuilder, propertyName: String, maybeValue: Option[IterableOnce[JsConvertible]]): Unit = {
    if (maybeValue.isDefined) appendJsArrayMember(sb,propertyName,maybeValue.get)
  }

  //--- internal methods

  protected def checkObjectMemberSep (sb: StringBuilder): Unit = {
    val len = sb.length()
    if (len > 0) {
      val c = sb.charAt(len-1)
      if (c == ';') {
        sb.setCharAt(len-1, ',')
      } else if (!(c == '{' || c == ',' || c == '[')) {
        sb.append(',')
      }
    }
  }
}
