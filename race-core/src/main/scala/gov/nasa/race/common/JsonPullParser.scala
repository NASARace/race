/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.io.PrintStream

import gov.nasa.race.common.inlined.{RangeStack, Slice}

import scala.annotation.switch
import scala.collection.mutable.Stack

//--- examples:
// true
// 42.0
// "another primitive"
// [1,2,3]
// {"type":"position","ident":"VOI423","air_ground":"A","alt":"7400","clock":"1440096358","id":"VOI423-1439922000-schedule-0001","gs":"184","heading":"295","hexid":"0D081C","lat":"20.54741","lon":"-103.36899","updateType":"A","altChange":"C"}
// {"time":1568090280,"states":[["4b1803","SWR5181 ","Switzerland",1568090266,1568090279,8.5594,47.4533,9197.34,true,0,5,null,null,null,"1021",false,0],["4b1801","SWR193V ","Switzerland",1568090271,1568090277,8.5602,47.4462,403.86,true,0,244,null,null,null,"2000",false,0]]}

class JsonParseException (msg: String) extends RuntimeException(msg)

/**
  * a pull parser for regular JSON, modeled after the XmlPullParser2
  */
abstract class JsonPullParser {

  protected var data: Array[Byte] = Array.empty[Byte]  // the (abstract) data (UTF-8), might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  protected val path = new RangeStack(32)  // member (slice) stack
  protected val env = Stack.empty[State]   // state stack

  sealed abstract class State {
    def parseNextValue: Boolean = false
  }

  class ObjectState extends State {

    // idx is on opening '{' , preceding ',' or closing '}'
    override def parseNextValue: Boolean = {
      val data = JsonPullParser.this.data
      var i0 = idx

      if (data(i0) == '}') {
        if (path.nonEmpty) path.pop
        env.pop
        if (env.isEmpty) { // done
          state = endState
          // no need to change idx
          false

        } else { // end of nested object
          state = env.top
          idx = skipWs(i0 + 1)
          state.parseNextValue
        }

      } else {
        data(i0) match {
          case ',' | '{' => i0 = skipWs(i0+1)
          case _ => throw new JsonParseException(s"expected start of member at '.. ${context(i0)} ..'")
        }

        if (data(i0) != '"') throw new JsonParseException(s"member name expected at ${context(i0)}")
        i0 += 1
        var i1 = skipToEndOfString(i0)
        member.setRange(i0, i1-i0)

        i0 = skipWs(i1+1)
        if (data(i0) != ':') throw new JsonParseException(s"':' expected after member name at ${context(i0)}")

        i0 = skipWs(i0+1)
        data(i0) match {
          //--- new environment
          case '{' => // value is object
            path.push(member.offset,member.length)
            env.push(state)
            idx = i0
            true
          case '[' => // value is array
            state = arrState
            env.push(state)
            idx = i0
            true

          //--- simple values
          case '"' =>
            i0 += 1
            i1 = skipToEndOfString(i0)
            value.setRange(i0, i1-i0)
            isStringValue = true
            idx = skipWs(skipToValueSep(i1+1))

            true

          case _ =>
            i1 = skipToValueSep(i0+1)
            value.setRange(i0, i1-i0)
            isStringValue = false
            idx = skipWs(i1)
            true
        }
      }
    }

    def skipToValueSep(i0: Int): Int = {
      val data = JsonPullParser.this.data
      var i = i0
      var b = data(i)
      while (b != ',' && b != '}' && !isWs(b)) {
        i += 1
        b = data(i)
      }
      i
    }
  }

  class ArrayState extends State {

    // idx is on opening '[' , preceding ',' or closing ']'
    override def parseNextValue: Boolean = {
      val data = JsonPullParser.this.data
      var i0 = idx

      if (data(i0) == ']') {
        env.pop
        if (env.isEmpty) { // done
          state = endState
          false

        } else { // end of nested array
          state = env.top
          idx = skipWs(i0 + 1)
          state.parseNextValue
        }

      } else {
        data(i0) match {
          case ',' | '[' => i0 = skipWs(i0 + 1)
          case _ => throw new JsonParseException(s"expected start of array element at '.. ${context(i0)} ..'")
        }

        data(i0) match {
          //--- new environment
          case '{' => // element value is object
            path.push(member.offset,member.length)
            state = objState
            env.push(state)
            idx = i0
            true
          case '[' => // element value is nested array
            env.push(state)
            idx = i0
            true

          //--- simple values
          case '"' =>
            i0 += 1
            val i1 = skipToEndOfString(i0)
            value.setRange(i0, i1-i0)
            isStringValue = true
            idx = skipWs(skipToValueSep(i1+1))
            true

          case _ =>
            val i1 = skipToValueSep(i0+1)
            value.setRange(i0, i1-i0)
            isStringValue = false
            idx = skipWs(i1)
            true
        }
      }
    }

    def skipToValueSep(i0: Int): Int = {
      val data = JsonPullParser.this.data
      var i = i0
      var b = data(i)
      while (b != ',' && b != ']' && !isWs(b)) {
        i += 1
        b = data(i)
      }
      i
    }
  }

  class ValState extends State { // single value

    // idx is on first char of value
    override def parseNextValue: Boolean = {
      val data = JsonPullParser.this.data
      val i0 = idx

      if (data(i0) == '"') {  // string value
        idx = skipToEndOfString(i0+1)
        value.set(data,i0+1,idx-i0-1)

      } else { // number, bool or null
        idx = skipToSep(i0)
        value.set(data,i0,idx-i0)
      }
      member.clear // no member
      state = endState // done
      true
    }
  }

  class EndState extends State {
    override def parseNextValue: Boolean = false
  }

  protected final val objState = new ObjectState
  protected final val arrState = new ArrayState
  protected final val valState = new ValState
  protected final val endState = new EndState

  protected var state: State = endState

  var isStringValue = false

  val member = Slice.empty
  val value = Slice.empty   // holds primitive values (String,number,bool,null)


  //--- public interface

  def level: Int = env.size

  @inline final def isScalarValue: Boolean = value.nonEmpty

  @inline final def isInObject: Boolean = state == objState

  @inline final def isObjectValue: Boolean = data(idx) == '{'

  @inline final def isObjectEnd (objLevel: Int = env.size): Boolean = {
    (state == endState || (data(idx) == '}' && objLevel == env.size))
  }

  @inline final def isInArray: Boolean = state == arrState

  @inline final def isArrayValue: Boolean = data(idx) == '['

  @inline final def isArrayEnd (arrLevel: Int = env.size): Boolean = {
    (state == endState || (data(idx) == ']' && arrLevel == env.size))
  }

  @inline final def elementHasMoreValues: Boolean = idx < limit && data(idx) == ','

  @inline final def notDone = state != endState

  // ?? do we need a parseNextMember/parseNextLevelMember ??

  def parseNextValue: Boolean = {
    member.clearRange
    value.clearRange
    state.parseNextValue
  }


  //--- internal methods

  def context (i: Int, len: Int = 20): String = {
    val i0 = i
    val i1 = Math.min(i0+len, data.length)
    new String(data, i0, i1-i0)
  }

  protected def setData (newData: Array[Byte]): Unit = setData(newData,newData.length)

  protected def setData (newData: Array[Byte], newLimit: Int): Unit = {
    data = newData
    limit = newLimit

    member.data = newData
    value.data = newData
  }

  protected def clear: Unit = {
    idx = 0
    limit = 0
    path.clear
    env.clear
    member.clear
    value.clear
    state = null
  }

  @inline protected final def isWs (c: Byte): Boolean = {
    c == ' ' || c == '\n' || c == '\r' || c == '\t'
  }

  @inline protected final def skipWs (i0: Int): Int = {
    val data = this.data

    var i = i0
    while (isWs(data(i))) i += 1
    i
  }

  @inline protected final def skipWsChecked (i0: Int): Int = {
    val data = this.data

    var i = i0
    while (i < limit && isWs(data(i))) i += 1
    i
  }

  @inline protected final def skipToEndOfString (i0: Int): Int = {
    val data = this.data
    var c: Byte = 0

    var i = i0
    while ({c = data(i); c != '"'}) {
      if (c == '\\') i += 2 else i += 1
    }
    i
  }

  // skip to end of non-string value
  @inline protected final def skipToSep (i0: Int): Int = {
    val data = this.data
    var c: Byte = 0

    var i = i0
    while (i < limit) {
      c = data(i)
      if (isWs(c) || c == '}' || c == ']' || c == ',') return i
      i += 1
    }
    i
  }

  protected def seekStart: Int = {
    val data = this.data

    var i = skipWs(0)
    if (i < limit) {
      val b = data(i)
      if (b == '{') { // object
        state = objState
        env.push(state)
      } else if (b == '[') { // array
        state = arrState
        env.push(state)
      } else { // single value
        state = valState
      }
      i

    } else { // no data
      state = endState
      -1
    }
  }

  def printOn (ps: PrintStream): Unit = {
    var indent: Int = 0

    def printIndent: Unit = {
      for (i <- 1 to indent) ps.print("  ")
    }

    def printValue: Unit = {
      if (isScalarValue) {
        if (isStringValue) ps.print(s""""$value"""") else ps.print(value)
        if (elementHasMoreValues) ps.println(',') else ps.println

      } else { // object or array
        if (isObjectValue) {
          printObject
        } else {
          printArray
        }
      }
    }

    def printObject: Unit = {
      indent += 1
      ps.println('{')
      while (!isObjectEnd()){
        if (parseNextValue) {
          printIndent
          ps.print(s""""$member": """)
          printValue
        }
      }
      indent -= 1
      printIndent
      ps.println('}')
    }

    def printArray: Unit = {
      indent += 1
      ps.println('[')
      while (!isArrayEnd()){
        if (parseNextValue) {
          printIndent
          printValue
        }
      }
      indent -= 1
      printIndent
      ps.println(']')
    }

    if (isInObject) printObject
    else if (isInArray) printArray
    else printValue
  }
}

class StrJsonPullParser extends JsonPullParser {
  def initialize (s: String): Boolean = {
    clear
    setData(s.getBytes)

    idx = seekStart
    idx >= 0
  }
}