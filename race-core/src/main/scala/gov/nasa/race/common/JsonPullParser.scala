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

object JsonPullParser {
  sealed abstract trait JsonParseResult {
    def isDefined: Boolean = true
    def isScalarValue: Boolean = false
  }
  sealed abstract trait AggregateStart extends JsonParseResult
  sealed abstract trait AggregateEnd extends JsonParseResult
  sealed abstract trait ScalarValue extends JsonParseResult {
    override def isScalarValue: Boolean = true
  }

  case object ObjectStart extends AggregateStart
  case object ObjectEnd extends AggregateEnd
  case object ArrayStart extends AggregateStart
  case object ArrayEnd extends AggregateEnd
  case object QuotedValue extends ScalarValue
  case object UnQuotedValue extends ScalarValue
  case object NoValue extends JsonParseResult {
    override def isDefined: Boolean = false
  }

  final val trueValue = Slice("true")
  final val falseValue = Slice("false")
  final val nullValue = Slice("null")
}

/**
  * a pull parser for regular JSON, modeled after the XmlPullParser2
  */
abstract class JsonPullParser {
  import JsonPullParser._

  protected var data: Array[Byte] = Array.empty[Byte]  // the (abstract) data (UTF-8), might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  protected val path = new RangeStack(32)  // member (slice) stack
  protected val env = Stack.empty[State]   // state stack


  sealed abstract class State {
    def parseNextValue: JsonParseResult = NoValue
    override def toString: String = getClass.getSimpleName
  }

  class ObjectState extends State {

    // idx is on opening '{' , preceding ',' or closing '}'
    override def parseNextValue: JsonParseResult = {
      val data = JsonPullParser.this.data
      var i0 = idx

      if (data(i0) == '}') {
        if (path.nonEmpty) path.pop
        env.pop
        if (env.isEmpty) { // done
          state = endState
          // no need to change idx
          ObjectEnd
        } else { // end of nested object
          state = env.top
          idx = skipWs(i0 + 1)
          ObjectEnd
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
            ObjectStart
          case '[' => // value is array
            state = arrState
            env.push(state)
            idx = i0
            ArrayStart

          //--- simple values
          case '"' =>
            i0 += 1
            i1 = skipToEndOfString(i0)
            value.setRange(i0, i1-i0)
            isQuotedValue = true
            idx = skipWs(skipToValueSep(i1+1))
            QuotedValue

          case _ =>
            i1 = skipToValueSep(i0+1)
            value.setRange(i0, i1-i0)
            isQuotedValue = false
            idx = skipWs(i1)
            UnQuotedValue
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

  class InitialObjectState extends ObjectState {
    override def parseNextValue: JsonParseResult = {
      state = objState
      ObjectStart
    }
  }

  class ArrayState extends State {

    // idx is on opening '[' , preceding ',' or closing ']'
    override def parseNextValue: JsonParseResult = {
      val data = JsonPullParser.this.data
      var i0 = idx

      if (data(i0) == ']') {
        env.pop
        if (env.isEmpty) { // done
          state = endState
          ArrayEnd

        } else { // end of nested array
          state = env.top
          idx = skipWs(i0 + 1)
          ArrayEnd
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
            ObjectStart
          case '[' => // element value is nested array
            env.push(state)
            idx = i0
            ArrayStart

          //--- simple values
          case '"' =>
            i0 += 1
            val i1 = skipToEndOfString(i0)
            value.setRange(i0, i1-i0)
            isQuotedValue = true
            idx = skipWs(skipToValueSep(i1+1))
            QuotedValue

          case _ =>
            val i1 = skipToValueSep(i0+1)
            value.setRange(i0, i1-i0)
            isQuotedValue = false
            idx = skipWs(i1)
            UnQuotedValue
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

  class InitialArrayState extends ArrayState {
    override def parseNextValue: JsonParseResult = {
      state = arrState
      ArrayStart
    }
  }

  class ValState extends State { // single value

    // idx is on first char of value
    override def parseNextValue: JsonParseResult = {
      val data = JsonPullParser.this.data
      val i0 = idx

      member.clear // no member
      state = endState // done

      if (data(i0) == '"') {  // string value, could be "true","false" or "null"
        idx = skipToEndOfString(i0+1)
        value.set(data,i0+1,idx-i0-1)
        QuotedValue

      } else { // number
        idx = skipToSep(i0)
        value.set(data,i0,idx-i0)
        UnQuotedValue
      }
    }
  }

  class EndState extends State {
    override def parseNextValue: JsonParseResult = NoValue
  }

  protected final val initObjState = new InitialObjectState
  protected final val initArrState = new InitialArrayState
  protected final val objState = new ObjectState
  protected final val arrState = new ArrayState
  protected final val valState = new ValState
  protected final val endState = new EndState

  protected var state: State = endState

  var isQuotedValue = false

  val member = Slice.empty
  val value = Slice.empty   // holds primitive values (String,number,bool,null)


  //--- public interface

  def level: Int = env.size

  @inline final def isScalarValue: Boolean = value.nonEmpty

  @inline final def isInObject: Boolean = (state == objState || state == initObjState)

  @inline final def isObjectValue: Boolean = data(idx) == '{'

  @inline final def isObjectEnd (objLevel: Int = env.size): Boolean = {
    //(state == endState || (data(idx) == '}' && objLevel == env.size))
    (data(idx) == '}' && objLevel == env.size)
  }

  @inline final def isLevelStart: Boolean = (data(idx)|32) == '{'
  @inline final def isLevelEnd: Boolean = (data(idx)|32) == '}'

  @inline final def isInArray: Boolean = (state == arrState || state == initArrState)

  @inline final def isArrayValue: Boolean = data(idx) == '['

  @inline final def isArrayEnd (arrLevel: Int = env.size): Boolean = {
    //(state == endState || (data(idx) == ']' && arrLevel == env.size))
    (data(idx) == ']' && arrLevel == env.size)
  }

  @inline final def elementHasMoreValues: Boolean = idx < limit && data(idx) == ','

  @inline final def notDone = state != endState

  @inline final def isDone = state ==  endState

  @inline final def parseNextValue: JsonParseResult = {
    member.clearRange
    value.clearRange
    state.parseNextValue
  }

  final def readNextMemberValue (name: Slice): JsonParseResult = {
    val res = parseNextValue
    if (name.length > 0 && name != member) throw new JsonParseException(s"expected member $name got $member")
    res
  }

  def parseAllValues (pf: PartialFunction[JsonParseResult,Unit]): Unit = {
    while (notDone){
      val v = parseNextValue
      if (v.isDefined) pf.apply(v) else return
    }
  }

  def parseScalarMember (name: Slice): Boolean = {
    isInObject && parseNextValue.isScalarValue && member == name
  }

  //--- those can throw exceptions that have to be handled in the caller

  @inline final def matchObjectStart = {
    if (parseNextValue != ObjectStart) throw new JsonParseException("not on object start")
  }

  @inline final def matchObjectEnd = {
    if (parseNextValue != ObjectEnd) throw new JsonParseException("not on object end")
  }

  @inline final def matchArrayStart = {
    if (parseNextValue != ArrayStart) throw new JsonParseException("not on array start")
  }

  @inline final def matchArrayEnd = {
    if (parseNextValue != ArrayEnd) throw new JsonParseException("not on array end")
  }

  @inline final def readQuotedValue: Slice = {
    if (parseNextValue == QuotedValue) value else throw new JsonParseException(s"not a quoted value: '$value'")
  }
  @inline final def readQuotedMember(name: Slice): Slice = {
    val res = parseNextValue
    if (member != name) throw new JsonParseException(s"expected member '$name' got '$member'")
    if (res == QuotedValue) value else throw new JsonParseException(s"not a quoted value: '$value'")
  }

  @inline final def readUnQuotedValue: Slice = {
    if (parseNextValue == UnQuotedValue) value else throw new JsonParseException(s"not a un-quoted value: ''$value''")
  }
  @inline final def readUnQuotedMember(name: Slice): Slice = {
    val res = parseNextValue
    if (member != name) throw new JsonParseException(s"expected member '$name' got '$member'")
    if (res == UnQuotedValue) value else throw new JsonParseException(s"not a un-quoted value: ''$value''")
  }

  @inline final def parseArrayStart: Boolean = parseNextValue == ArrayStart
  @inline final def parseMemberArrayStart(name: Slice): Boolean = (parseNextValue == ArrayStart && member == name)

  @inline final def parseObjectStart: Boolean = parseNextValue == ObjectStart
  @inline final def parseMemberObjectStart(name: Slice): Boolean = (parseNextValue == ObjectStart && member == name)


  def readMemberArray (name: Slice)(f: =>Unit): Unit = {
    if (readNextMemberValue(name) == ArrayStart) {
      val endLevel = level
      do {
        f // this is supposed to parse ONE array element
      } while (notDone && (level >= endLevel && elementHasMoreValues))
      if (parseNextValue != ArrayEnd) throw new JsonParseException(s"invalid array termination for $name")
    } else throw new JsonParseException(s"not an array value for '$member'")
  }
  @inline final def readArray (f: => Unit): Unit = readMemberArray(Slice.EmptySlice)(f)


  def readMemberObject (name: Slice)(f: =>Unit): Unit = {
    if (readNextMemberValue(name) == ObjectStart) {
      val endLevel = level
      f // this is supposed to call parseNextValue on ALL members of object (possibly skipping some)
      if (notDone && (level != endLevel || parseNextValue != ObjectEnd)) throw new JsonParseException("object not parsed correctly: $name")
    } else throw new JsonParseException(s"not an object value for '$member'")
  }
  @inline final def readObject(f: =>Unit): Unit = readMemberObject(Slice.EmptySlice)(f)

  def skipToEndOfLevel (lvl0: Int): Unit = {
    var lvl = env.size
    val tgtLevel = if (isLevelStart) lvl0+1 else lvl0
    var i = idx

    while (true) {
      if (data(i) == '"') i = skipToEndOfString(i+1)
      if (i < limit) {
        (data(i): @switch) match {
          case '{' | '[' =>
            lvl += 1
          case '}' | ']' =>
            lvl -= 1
            if (lvl < tgtLevel) { idx = i; return }
          case _ =>
        }
        i += 1
      } else { idx = i; return }
    }
  }

  @inline def skipToEndOfCurrentLevel = skipToEndOfLevel(env.size)

  def skip (nValues: Int): Unit = {
    var lvl = env.size
    val tgtLevel = if (isLevelStart) lvl+1 else lvl
    var remaining = nValues
    var i = if (data(idx)==',') idx+1 else idx

    while (true) {
      if (data(i) == '"') i = skipToEndOfString(i+1)
      if (i < limit) {
        (data(i): @switch) match {
          case '{' | '[' =>
            lvl += 1
          case '}' | ']' =>
            lvl -= 1
            if (lvl < tgtLevel) { idx = i; return }
          case ',' =>
            if (lvl == tgtLevel) {
              remaining -= 1
              if (remaining == 0) {
                idx = i; return
              } else remaining -= 1
            }
          case _ =>
        }
        i += 1
      } else { idx = i; return }
    }
  }

  //--- internal methods

  @inline final protected def checkMember (name: Slice): Unit = {
    if (member != name) throw new JsonParseException(s"expected member '$name' got '$member'")
  }

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
        state = initObjState
        env.push(objState) // note that we don't push the initObjState since its only purpose is to replace itself
      } else if (b == '[') { // array
        state = initArrState
        env.push(arrState)
      } else { // single value
        state = valState
      }
      i

    } else { // no data
      state = endState
      -1
    }
  }


  //--- test and debugging

  def printAllValues: Unit = {
    def printValue (res: String) = {
      //print(s"  $idx '${data(idx).toChar}' @ $level ($state) => ")

      print(res)
      print(" ")
      if (member.nonEmpty) print(s""""$member": """)
      if (isScalarValue) {
        if (isQuotedValue) print(s"""$value""") else print(value.toString)
      }
      println
    }

    parseAllValues {
      case ArrayStart => printValue("ArrayStart")
      case ArrayEnd => printValue("ArrayEnd")
      case ObjectStart => printValue("ObjectStart")
      case ObjectEnd => printValue("ObjectEnd")
      case QuotedValue => printValue("QuotedValue")
      case UnQuotedValue => printValue("UnQuotedValue")
    }
  }

  def printOn (ps: PrintStream): Unit = {
    var indent = 0

    def printIndent: Unit = for (i <- 1 to indent) ps.print("  ")

    def printMember = if (member.nonEmpty) ps.print(s""""$member": """)

    while (true) {
      parseNextValue match {
        case ArrayStart =>
          printIndent
          printMember
          indent += 1
          ps.println('[')

        case ArrayEnd =>
          indent -= 1
          printIndent
          ps.println(']')

        case ObjectStart =>
          printIndent
          printMember
          indent += 1
          ps.println('{')

        case ObjectEnd =>
          indent -= 1
          printIndent
          ps.println('}')

        case v:ScalarValue =>
          printIndent
          printMember
          if (isQuotedValue) ps.print(s""""$value"""") else ps.print(value)
          if (elementHasMoreValues) ps.println(',') else ps.println

        case NoValue => return
      }
    }
  }
}

/**
  * unbuffered JsonPullParser processing String input
  */
class StringJsonPullParser extends JsonPullParser {
  def initialize (s: String): Boolean = {
    clear
    setData(s.getBytes)

    idx = seekStart
    idx >= 0
  }
}

/**
  * buffered JsonPullParser processing String input
  */
class BufferedStringJsonPullParser (initBufSize: Int = 8192) extends JsonPullParser {

  protected val bb = new UTF8Buffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear
    setData(bb.data, bb.length)

    idx = seekStart
    idx >= 0
  }
}

/**
  * buffered JsonPullParser processing ASCII String input
  */
class BufferedASCIIStringJsonPullParser (initBufSize: Int = 8192) extends JsonPullParser {

  protected val bb = new ASCIIBuffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear
    setData(bb.data, bb.length)

    idx = seekStart
    idx >= 0
  }
}

/**
  * unbuffered JsonPullParser processing utf-8 byte array input
  */
class UTF8JsonPullParser extends JsonPullParser {
  def initialize (bs: Array[Byte], limit: Int): Boolean = {
    clear
    setData(bs,limit)

    idx = seekStart
    idx >= 0
  }

  def initialize (bs: Array[Byte]): Boolean = initialize(bs,bs.length)
}