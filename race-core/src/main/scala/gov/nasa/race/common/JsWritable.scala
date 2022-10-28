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

/**
 * something that can produce a JavaScript string
 */
trait JsWritable {

  def appendJsMembers (sb: StringBuffer): Unit

  def appendJsObject (sb: StringBuffer): Unit = {
    sb.append('{')
    appendJsMembers(sb)
    sb.append('}')
  }

  def toJsObject(): String = {
    val sb = new StringBuffer()
    appendJsObject(sb)
    sb.toString
  }

  def toJs: String = {
    val sb = new StringBuffer()
    appendJsMembers(sb)
    sb.toString
  }

  def appendString(sb: StringBuffer, s: String): Unit = {
    sb.append('\'')
    sb.append(s)
    sb.append('\'')
  }

  def appendStringMember(sb: StringBuffer, propertyName: String, value: String): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendString(sb, value)
  }

  def appendIntMember (sb: StringBuffer, propertyName: String, value: Int): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    sb.append(value)
  }

  def appendDoubleMember (sb: StringBuffer, propertyName: String, value: Double): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    sb.append(value)
  }

  def appendBooleanMember (sb: StringBuffer, propertyName: String, value: Boolean): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    sb.append(value)
  }

  def appendObject (sb: StringBuffer, o: JsWritable): Unit = {
    if (o != null) {
      sb.append('{')
      o.appendJsMembers(sb)
      sb.append('}')
    } else {
      sb.append("null")
    }
  }

  def appendObjectMember (sb: StringBuffer, propertyName: String, o: JsWritable): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendObject(sb,o)
  }

  def appendOptionalObjectMember (sb: StringBuffer, propertyName: String, o: Option[JsWritable]): Unit = {
    if (o.isDefined) appendObjectMember(sb,propertyName,o.get)
  }

  def appendArray (sb: StringBuffer, list: Seq[JsWritable]): Unit = {
    sb.append('[')
    if (list.nonEmpty) {
      list.foreach { o =>
        o.appendJsObject(sb)
        sb.append(',')
      }
      sb.setLength(sb.length() - 1) // remove last comma
    }
    sb.append(']')
  }

  def appendArrayMember (sb: StringBuffer, propertyName: String, list: Seq[JsWritable]): Unit = {
    checkObjectMemberSep(sb)
    sb.append(propertyName)
    sb.append(':')
    appendArray(sb,list)
  }

  def checkObjectMemberSep (sb: StringBuffer): Unit = {
    val len = sb.length()
    if (len > 0) {
      val c = sb.charAt(len-1)
      if (c != '{' && c != ',' && c != '[') {
        sb.append(',')
      }
    }
  }
}
