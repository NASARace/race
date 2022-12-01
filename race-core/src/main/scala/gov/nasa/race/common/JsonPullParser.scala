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

import gov.nasa.race.{Failure, ResultValue, SuccessValue}

import java.io.PrintStream
import gov.nasa.race.uom.DateTime

import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Stack}

//--- examples:
// true
// 42.0
// "another primitive"
// [1,2,3]
// {"type":"position","ident":"VOI423","air_ground":"A","alt":"7400","clock":"1440096358","id":"VOI423-1439922000-schedule-0001","gs":"184","heading":"295","hexid":"0D081C","lat":"20.54741","lon":"-103.36899","updateType":"A","altChange":"C"}
// {"time":1568090280,"states":[["4b1803","SWR5181 ","Switzerland",1568090266,1568090279,8.5594,47.4533,9197.34,true,0,5,null,null,null,"1021",false,0],["4b1801","SWR193V ","Switzerland",1568090271,1568090277,8.5602,47.4462,403.86,true,0,244,null,null,null,"2000",false,0]]}

class JsonParseException (msg: String) extends RuntimeException(msg)

object JsonPullParser {

  //--- type system for explicit parsing of Json token streams

  sealed trait JsonParseResult {
    def isDefined: Boolean = true
    def isScalarValue: Boolean = false
    def isAggregateDelimiter: Boolean = false
  }

  sealed trait AggregateDelimiter extends JsonParseResult {
    override def isAggregateDelimiter = true
    def isStartBracket: Boolean
    def matchesParseResult(res: JsonParseResult): Boolean = (this eq res) || (this == res)
  }
  sealed trait AggregateStart extends AggregateDelimiter {
    override def isStartBracket = true
    val endDelimiter: AggregateEnd
    def ensureEnd(p: JsonPullParser): Unit
  }
  sealed trait AggregateEnd extends AggregateDelimiter {
    override def isStartBracket = false
    def char: Byte
  }

  case object ObjectStart extends AggregateStart {
    val endDelimiter: AggregateEnd = ObjectEnd
    override def ensureEnd(p: JsonPullParser): Unit = p.ensureNextIsObjectEnd()
  }
  case object ObjectEnd extends AggregateEnd {
    override def char = '}'
  }

  case object ArrayStart extends AggregateStart {
    val endDelimiter: AggregateEnd = ArrayEnd
    override def ensureEnd(p: JsonPullParser): Unit = p.ensureNextIsArrayEnd()
  }
  case object ArrayEnd extends AggregateEnd {
    override def char = ']'
  }

  // this is an artificial construct to support remainder parsing
  case object NoStart extends AggregateStart {
    val endDelimiter: AggregateEnd = NoEnd
    override def matchesParseResult(res: JsonParseResult): Boolean = true // always matches without read
    override def ensureEnd(p: JsonPullParser): Unit = {} // no check, no read
  }
  case object NoEnd extends AggregateEnd {
    override def char = ' '
  }

  sealed trait ScalarValue extends JsonParseResult {
    override def isScalarValue: Boolean = true
  }
  case object QuotedValue extends ScalarValue
  case object UnQuotedValue extends ScalarValue

  case object NoValue extends JsonParseResult {
    override def isDefined: Boolean = false
  }

  final val trueValue = ConstAsciiSlice("true")
  final val falseValue = ConstAsciiSlice("false")
  final val nullValue = ConstAsciiSlice("null")
}

/**
  * a pull parser for regular JSON, modeled after the XmlPullParser2
  *
  * this parser supports three APIs with increasing level of abstraction:
  *
  * (1) low level stream
  * (2) mid level readXX functions returning Scala types
  * (3) high level parsing into a JsonValue structure (AST)
  *
  * TODO - the mid-level API needs substantial cleanup. Much of it seems to deal with sequential parsing of
  * JSON objects, which is against the spec (json objects are unordered collections ala Map)
  */
abstract class JsonPullParser extends LogWriter with Thrower {
  import JsonPullParser._

  class ParserState {
    val _idx = idx
    val _state = state
    val _pathSize = path.size
    val _envSize = env.size
    val _lastResult = lastResult

    def restore(): Unit = {
      idx = _idx
      state = _state
      if (path.size > _pathSize) {
        var i = path.size - _pathSize
        while (i > 0) { path.pop(); i -= 1 }
      }
      if (env.size > _envSize) {
        var i = env.size - _envSize
        while (i > 0) { env.pop(); i -= 1 }
      }
      lastResult = _lastResult
    }
  }

  protected var data: Array[Byte] = Array.empty[Byte]  // the (abstract) data (UTF-8), might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  protected val path = new RangeStack(32)  // member (slice) stack
  protected val env = Stack.empty[State]   // state stack
  protected var lastResult: JsonParseResult = NoValue // needs to be protected to ensure integrity

  override def exception(msg: String) = {
    new JsonParseException(s"$msg around '${dataContext(idx)}' ($idx)")
  }

  def tryParse[T](err: JsonParseException=>Unit)(f: =>T): Option[T] = {
    try {
      Some(f)
    } catch {
      case x: JsonParseException =>
        err(x)
        None
    }
  }

  //--- mostly for debugging
  // use sparingly - those allocate and hence defeat our purpose
  def dataAsString: String = new String(data,0,limit)
  def dataContext (i0: Int, len: Int=24): String = {
    val maxLen = Math.min(len,limit-i0)
    var s = new String(data,i0,maxLen)
    if (i0 > 0) s = "..." + s
    if (i0 + maxLen < limit) s = s + "..."
    s
  }

  def remainingDataAsString: String = new String(data,idx,limit-idx)

  def getLastResult: JsonParseResult = lastResult
  def getIdx: Int = idx
  def dumpEnv: Unit = println(s"""env = [${env.mkString(",")}]""")
  def envDepth: Int = env.size
  def dumpNext(): JsonParseResult = {
    val res = readNext()
    println(s"""($res, [${env.mkString(",")}]) => "$member": "$value")""")
    res
  }
  def assertNext (res: JsonParseResult, depth: Int, m: String, v: String): Unit = {
    assert(dumpNext() == res)
    assert(env.size == depth)
    assert(member =:= m)
    assert(value =:= v)
  }

  sealed abstract class State {
    def readNext(): JsonParseResult = NoValue
    override def toString: String = getClass.getSimpleName
  }

  class ObjectState extends State {

    // idx is on opening '{' , preceding ',' or closing '}'
    override def readNext(): JsonParseResult = {
      val data = JsonPullParser.this.data
      var i0 = idx

      val c = data(i0)
      if (c == ',' || c == '{') i0 = skipWs(i0+1)

      if (data(i0) == '}') {
        if (path.nonEmpty) path.pop()
        env.pop()
        if (env.isEmpty) { // done
          state = endState
          // no need to change idx
          ObjectEnd
        } else { // end of nested object
          state = env.top
          idx = skipWsChecked(i0 + 1)
          ObjectEnd
        }

      } else {
        if (data(i0) != '"') throw exception(s"member name expected at ${context(i0)}")
        i0 += 1
        var i1 = skipToEndOfString(i0)
        member.setRange(i0, i1-i0)

        i0 = skipWs(i1+1)
        if (data(i0) != ':') throw exception(s"':' expected after member name at ${context(i0)}")

        i0 = skipWs(i0+1)
        data(i0) match {
          //--- new environment
          case '{' => // value is object
            path.push(member.off,member.len)
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
    override def readNext(): JsonParseResult = {
      state = objState
      ObjectStart
    }
  }

  class ArrayState extends State {

    // idx is on opening '[' , preceding ',' or closing ']'
    override def readNext(): JsonParseResult = {
      val data = JsonPullParser.this.data
      var i0 = idx

      val c = data(i0)
      if (c == ',' || c == '[') i0 = skipWs(i0 + 1)

      if (data(i0) == ']') {
        env.pop()
        if (env.isEmpty) { // done
          state = endState
          ArrayEnd

        } else { // end of nested array
          state = env.top
          idx = skipWsChecked(i0 + 1)
          ArrayEnd
        }

      } else {
        data(i0) match {
          //--- new environment
          case '{' => // element value is object
            path.push(member.off,member.len)
            state = objState
            env.push(state)
            idx = i0
            ObjectStart
          case '[' => // element value is nested array
            env.push(state)
            idx = i0
            ArrayStart

          //--- simple values
          case '"' => // quoted string
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
    override def readNext(): JsonParseResult = {
      state = arrState
      ArrayStart
    }
  }

  class ValState extends State { // single value

    // idx is on first char of value
    override def readNext(): JsonParseResult = {
      val data = JsonPullParser.this.data
      val i0 = idx

      member.clear() // no member
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
    override def readNext(): JsonParseResult = NoValue
  }

  protected final val initObjState = new InitialObjectState
  protected final val initArrState = new InitialArrayState
  protected final val objState = new ObjectState
  protected final val arrState = new ArrayState
  protected final val valState = new ValState
  protected final val endState = new EndState

  protected var state: State = endState

  var isQuotedValue = false

  val member = MutUtf8Slice.empty  // member name of last value (if any)
  val value = MutUtf8Slice.empty   // holds primitive values (String,number,bool,null)


  //--- public interface

  def level: Int = env.size

  @inline final def quotedValue: Utf8Slice = if (isQuotedValue) value else throw exception("not a quoted value")
  @inline final def unQuotedValue: Utf8Slice = if (value.nonEmpty && !isQuotedValue) value else throw exception("not an unQuoted value")

  @inline final def isScalarValue: Boolean = value.nonEmpty

  @inline final def isInObject: Boolean = (state == objState || state == initObjState)

  @inline final def isObjectValue: Boolean = data(idx) == '{'

  @inline final def isNull: Boolean = value == nullValue

  @inline final def isObjectEnd (objLevel: Int = env.size): Boolean = {
    //(state == endState || (data(idx) == '}' && objLevel == env.size))
    (data(idx) == '}' && objLevel == env.size)
  }

  @inline final def isLevelStart: Boolean = (data(idx)|32) == '{'          // '[' or '{'
  @inline final def isLevelEnd: Boolean = (data(idx)|32) == '}'            // ']' or '}'

  @inline final def isInArray: Boolean = (state == arrState || state == initArrState)

  @inline final def isArrayValue: Boolean = data(idx) == '['

  @inline final def isArrayEnd (arrLevel: Int = env.size): Boolean = {
    //(state == endState || (data(idx) == ']' && arrLevel == env.size))
    (data(idx) == ']' && arrLevel == env.size)
  }

  @inline final def aggregateHasMoreValues: Boolean = {
    // FIXME - does not hold for first element
    idx < limit && data(idx) == ','
  }

  @inline final def peek: Byte = {
    if (idx < limit) data(idx) else 0
  }

  final def peekNext: Byte = {
    var i = idx+1
    while (i < limit && data(i) <= ' ') i += 1
    if (i < limit) data(i) else 0
  }

  @inline final def notDone = state != endState

  @inline final def isDone = state ==  endState

  /**
    * this is the low level API workhorse - read the next JsonValue, setting member, value and lastResult accordingly
    */
  @inline final def readNext(): JsonParseResult = {
    member.clearRange()
    value.clearRange()
    val res = state.readNext()
    lastResult = res
    res
  }

  final def readNextMember(name: ByteSlice): JsonParseResult = {
    val res = readNext()
    if (name.len > 0 && name != member) throw exception(s"expected member $name got $member")
    res
  }

  def matchNext(pf: PartialFunction[JsonParseResult,Unit]): Unit = {
    while (notDone){
      val v = readNext()
      if (v.isDefined) pf.apply(v) else return
    }
  }

  //--- mid level API - parse into standard Scala types

  //--- read and check result without returning anything, throwing exceptions if there is a mis-match

  @inline final def ensureNext(expected: JsonParseResult): Unit = {
    val res = readNext()
    if (res != expected) throw exception(s"expected '${expected.getClass.getSimpleName}' got '${res.getClass.getSimpleName}'")
  }
  @inline final def ensureNextIsObjectStart(): Unit = ensureNext(ObjectStart)
  @inline final def ensureNextIsObjectEnd() = ensureNext(ObjectEnd)
  @inline final def ensureNextIsArrayStart() = ensureNext(ArrayStart)
  @inline final def ensureNextIsArrayEnd() = ensureNext(ArrayEnd)


  @inline final def readMemberName(): Utf8Slice = {
    readNext()
    member
  }

  @inline final def readObjectMemberName(): Utf8Slice = {
    val res = readNext()
    if (res != ObjectStart || member.isEmpty) throw exception(s"expected object member, got '${res.getClass.getSimpleName}'")
    member
  }

  @inline final def readArrayMemberName(): Utf8Slice = {
    val res = readNext()
    if (res != ArrayStart || member.isEmpty) throw exception(s"expected array member, got '${res.getClass.getSimpleName}'")
    member
  }
  @inline final def readArrayMemberName(name: ByteSlice): Utf8Slice = {
    val res = readNext()
    if (member != name) throw exception(s"expected member '$name' got '$member'")
    if (res != ArrayStart || member.isEmpty) throw exception(s"expected array member, got '${res.getClass.getSimpleName}'")
    member
  }

  @inline final def readQuotedValue(): Utf8Slice = {
    if (readNext() == QuotedValue) value else throw exception(s"not a quoted value: '$value'")
  }
  @inline final def readQuotedMember(name: ByteSlice): Utf8Slice = {
    val res = readNext()
    if (member != name) throw exception(s"expected member '$name' got '$member'")
    if (res == QuotedValue) value else throw exception(s"not a quoted value: '$value'")
  }

  @inline final def readUnQuotedValue(): Utf8Slice = {
    if (readNext() == UnQuotedValue) value else throw exception(s"not a un-quoted value: ''$value''")
  }
  @inline final def readUnQuotedMember(name: ByteSlice): Utf8Slice = {
    val res = readNext()
    if (member != name) throw exception(s"expected member '$name' got '$member'")
    if (res == UnQuotedValue) value else throw exception(s"not a un-quoted value: ''$value''")
  }

  final def readOptionalUnQuotedMember(name: ByteSlice): Option[Utf8Slice] = {
    val ps = new ParserState

    if (!isLevelEnd && isNextScalarMember(name)) {
      Some(value)
    } else {
      ps.restore()
      None
    }
  }

  final def readOptionalQuotedMember(name: ByteSlice): Option[Utf8Slice] = {
    val ps = new ParserState

    if (!isLevelEnd && isNextScalarMember(name)) {
      Some(value)
    } else {
      ps.restore()
      None
    }
  }

  //--- DateTime parsing

  def dateTimeValue: DateTime = {
    if (value.nonEmpty) {
      if (isQuotedValue) {
        DateTime.parseYMDTSlice(value)
      } else {
        // NOTE - that cuts out 01/01/1970 - 04/26/1970 in epoch millis
        val n = value.toLong
        if (n < 10000000000L) DateTime.ofEpochSeconds(n) else DateTime.ofEpochMillis(value.toLong)
      }
    } else {
      throw exception("expected DateTime value")
    }
  }

  /**
    * return next named DateTime member
    * this is the exception from the rule that we return values as slices since we accept
    * both ISO string such as "2020-06-29T16:06:01.000" and Long epoch values, i.e. clients
    * would have to check for UnQuotedValue and QuotedValue parse results
    */

  final def readDateTime (): DateTime = {
    readNext() match {
      case UnQuotedValue => DateTime.ofEpochMillis(value.toLong)
      case QuotedValue => DateTime.parseYMDTSlice(value)
      case _ => throw exception(s"not a valid DateTime spec: ''$value''")
    }
  }

  final def readDateTimeMember (name: ByteSlice): DateTime = {
    val res = readNext()
    if (member != name) throw exception(s"expected member '$name' got '$member'")

    if (res == UnQuotedValue) {
      DateTime.ofEpochMillis(value.toLong)
    } else if (res == QuotedValue) {
      DateTime.parseYMDTSlice(value)
    } else throw exception(s"not a valid DateTime spec: ''$value''")
  }

  final def readOptionalDateTimeMember (name: ByteSlice): Option[DateTime] = {
    val ps = new ParserState

    if (!isLevelEnd && isNextScalarMember(name)) {
      if (lastResult == UnQuotedValue) {
        Some(DateTime.ofEpochMillis(value.toLong))
      } else {
        Some(DateTime.parseYMDTSlice(value))
      }
    } else {
      ps.restore()
      None
    }
  }

  //--- those read and check the result type, returning a Boolean that can be used for conditional parsing

  @inline final def isNextScalarMember(name: ByteSlice): Boolean = {
    isInObject && readNext().isScalarValue && member == name
  }

  @inline final def isNextQuotedMember(name: ByteSlice): Boolean = {
    isNextScalarMember(name) && isQuotedValue
  }
  @inline final def isNextUnQuotedMember(name: ByteSlice): Boolean = {
    isNextScalarMember(name) && !isQuotedValue
  }

  @inline final def isNextArrayStart(): Boolean = readNext() == ArrayStart
  @inline final def isNextArrayStartMember(name: ByteSlice): Boolean = (readNext() == ArrayStart && member == name)

  @inline final def isNextObjectStart(): Boolean = readNext() == ObjectStart
  @inline final def isNextObjectStartMember(name: ByteSlice): Boolean = (readNext() == ObjectStart && member == name)

  // if the test fails use 'isScalarValue', 'isQuotedValue', 'isInObject', 'isInArray' to further check without advancing

  //--- these loop over all child elements, calling the provided function for each of them

  // FIXME - this does not handle empty aggregates
  def foreachInAggregate(res: JsonParseResult, expected: AggregateStart)(f: =>Unit): Unit = {
    if (expected.matchesParseResult(res)) {
      if (peekNext != expected.endDelimiter.char) {
        val endLevel = level
        do {
          f // this is supposed to parse/process ONE element of the container
        } while (notDone && (level >= endLevel && aggregateHasMoreValues))
      }
      expected.ensureEnd(this)
    } else throw exception(s"expected '${expected.getClass.getSimpleName}' got '${res.getClass.getSimpleName}'")
  }

  // if we are asking for a known member name
  @inline final def foreachInNextArrayMember(name: ByteSlice)(f: =>Unit): Unit = foreachInAggregate(readNextMember(name),ArrayStart)(f)

  // if we don't care for the member name
  @inline final def foreachInNextArray(f: =>Unit): Unit = foreachInAggregate(readNext(),ArrayStart)(f)

  /**
    * apply the given function to each element in the current array
    * note the function only processes the CURRENT element (i.e. should not call readNext())
    */
  final def foreachElementInCurrentArray (f: =>Unit): Unit = {
    foreachInAggregate(lastResult,ArrayStart) {
      readNext()
      f
    }
  }

  // if we are asking for a known member name
  @inline final def foreachInNextObjectMember(name: ByteSlice)(f: =>Unit): Unit = foreachInAggregate(readNextMember(name),ObjectStart)(f)

  // if we don't care for the member name
  @inline final def foreachInNextObject(f: =>Unit): Unit = foreachInAggregate(readNext(),ObjectStart)(f)

  /**
    * apply the given function to each member in the current object
    * note the function only processes the CURRENT member (i.e. should not call readNext())
    */
  final def foreachInCurrentObject(f: =>Unit): Unit = {
    foreachInAggregate(lastResult,ObjectStart) {
      readNext()
      f
    }
  }

  /**
    * apply the given partial function to each member in the current object
    * members for which the partial function is not defined are ignored
    * note the function only processes the CURRENT member (i.e. should not call readNext())
    */
  final def foreachMemberInCurrentObject (f: PartialFunction[CharSeqByteSlice,Unit]): Unit = {
    foreachInAggregate(lastResult, ObjectStart) {
      readNext()
      if (!isNull) f.applyOrElse(member, (_:CharSequence) => {})
    }
  }

  /**
   * apply the given partial function to all remaining members of the current object
   * this can be used to do value based parsing
   * NOTE this does not consume the end token
   * note though that JSON does not guarantee member order - use only if the producer is known or the order does not matter
   */
  final def foreachRemainingMember (f: PartialFunction[CharSeqByteSlice,Unit]): Unit = {
    foreachInAggregate(lastResult, NoStart) {
      readNext()
      if (!isNull) f.applyOrElse(member, (_: CharSequence) => {})
    }
  }

  //--- the provided function loops itself, i.e. processes the whole element (or a prefix thereof)

  def readAggregate[T](res: JsonParseResult, expected: AggregateStart)(f: => T): T = {
    if (res == expected) {
      val endLevel = level
      val res = f // parse whatever is required from this element (can be multiple items)
      if (level >= endLevel) {   // skip the rest if the provided function was not exhaustive
        skipToEndOfLevel(endLevel)
        ensureNext(expected.endDelimiter)
      }
      res
    } else throw exception(s"expected '${expected.getClass.getSimpleName}' got '${res.getClass.getSimpleName}'")
  }

  @inline final def readNextObjectMember[T](name: ByteSlice)(f: =>T): T = readAggregate(readNextMember(name), ObjectStart)(f)
  @inline final def readNextObject[T](f: =>T): T = readAggregate(readNext(), ObjectStart)(f)
  @inline final def readCurrentObject[T](f: =>T): T = readAggregate(lastResult, ObjectStart)(f)

  @inline final def readNextArrayMember[T](name: ByteSlice)(f: =>T): T = readAggregate(readNextMember(name), ArrayStart)(f)
  @inline final def readNextArray[T](f: =>T): T = readAggregate(readNext(), ArrayStart)(f)
  @inline final def readCurrentArray[T](f: =>T): T = readAggregate(lastResult, ArrayStart)(f)

  //--- those are purely for side effects of the provided funtion - TODO - do we need these
  @inline final def processNextArrayMember(name: ByteSlice)(f: =>Unit): Unit = readAggregate(readNextMember(name), ArrayStart)(f)
  @inline final def processNextArray(f: =>Unit): Unit = readAggregate(readNext(), ArrayStart)(f)
  @inline final def processCurrentArray(f: =>Unit): Unit = readAggregate(lastResult, ArrayStart)(f)

  @inline final def processNextObjectMember(name: ByteSlice)(f: =>Unit): Unit = readAggregate(readNextMember(name), ObjectStart)(f)
  @inline final def processNextObject(f: =>Unit): Unit = readAggregate(readNext(), ObjectStart)(f)
  @inline final def processCurrentObject(f: =>Unit): Unit = readAggregate(lastResult, ObjectStart)(f)

  def readObjectValueString(): String = {
    if (isObjectValue) {
      val i0 = idx
      skipPastAggregate()
      val i1 = idx
      new String(data,i0,i1-i0)
    } else throw exception("not an object")
  }

  def readOptionalObjectMember[T](name: ByteSlice)(f: =>T): Option[T] = {
    val ps = new ParserState

    if (!isLevelEnd && isNextObjectStartMember(name)) {
      Some(readCurrentObject(f))
    } else {
      ps.restore()
      None
    }
  }

  //--- reading arrays into provided mutable collection

  def readNextArrayMemberInto[U,T <:mutable.Growable[U]](name: ByteSlice, collection: T)(f: =>U): T = {
    foreachInNextArrayMember(name){ collection.addOne(f) };
    collection
  }
  def readNextArrayInto[U,T <:mutable.Growable[U]](collection: T)(f: =>U): T = {
    foreachInNextArray{ collection.addOne(f) };
    collection
  }
  def readCurrentArrayInto[U,T <:mutable.Growable[U]](collection: T)(f: =>U): T = {
    foreachElementInCurrentArray{ collection.addOne(f) };
    collection
  }

  def readOptionalArrayMemberInto[U,T <:mutable.Growable[U]](name: ByteSlice, collection: =>T)(f: =>U): Option[T] = {
    val ps = new ParserState

    if (!isLevelEnd && isNextArrayStartMember(name)) {
      val c = collection
      foreachElementInCurrentArray{ c.addOne(f) };
      Some(c)

    } else {
      ps.restore()
      None
    }
  }

  //--- specialized cases for reading arrays of same-type values

  //--- byte elements
  def readNextByteArrayMemberInto[T <:mutable.Growable[Byte]](name: ByteSlice, collection: T): T = {
    foreachInNextArrayMember(name)(collection.addOne(readUnQuotedValue().toByte)); collection
  }
  def readNextByteArrayInto[T <:mutable.Growable[Byte]](collection: T): T = {
    foreachInNextArray(collection.addOne(readUnQuotedValue().toByte)); collection
  }
  def readCurrentByteArrayInto[T <:mutable.Growable[Byte]](collection: T): T = {
    foreachElementInCurrentArray(collection.addOne(unQuotedValue.toByte)); collection
  }

  //--- Int elements
  def readNextIntArrayMemberInto[T <:mutable.Growable[Int]](name: ByteSlice, collection: T): T = {
    foreachInNextArrayMember(name)(collection.addOne(readUnQuotedValue().toInt)); collection
  }
  def readNextIntArrayInto[T <:mutable.Growable[Int]](collection: T): T = {
    foreachInNextArray(collection.addOne(readUnQuotedValue().toInt)); collection
  }
  def readCurrentIntArrayInto[T <:mutable.Growable[Int]](collection: T): T = {
    foreachElementInCurrentArray(collection.addOne(unQuotedValue.toInt)); collection
  }

  //--- Long elements
  def readNextLongArrayMemberInto[T <:mutable.Growable[Long]](name: ByteSlice, collection: T): T = {
    foreachInNextArrayMember(name)(collection.addOne(readUnQuotedValue().toLong)); collection
  }
  def readNextLongArrayInto[T <:mutable.Growable[Long]](collection: T): T = {
    foreachInNextArray(collection.addOne(readUnQuotedValue().toLong)); collection
  }
  def readCurrentLongArrayInto[T <:mutable.Growable[Long]](collection: T): T = {
    foreachElementInCurrentArray(collection.addOne(unQuotedValue.toLong)); collection
  }

  //--- Double elements
  def readNextDoubleArrayMemberInto[T <:mutable.Growable[Double]](name: ByteSlice, collection: T): T = {
    foreachInNextArrayMember(name)(collection.addOne(readUnQuotedValue().toDouble)); collection
  }
  def readNextDoubleArrayInto[T <:mutable.Growable[Double]](collection: T): T = {
    foreachInNextArray(collection.addOne(readUnQuotedValue().toDouble)); collection
  }
  def readCurrentDoubleArrayInto[T <:mutable.Growable[Double]](collection: T): T = {
    foreachElementInCurrentArray(collection.addOne(unQuotedValue.toDouble)); collection
  }

  //--- String elements
  def readNextStringArrayMemberInto[T <:mutable.Growable[String]](name: ByteSlice, collection: T): T = {
    foreachInNextArrayMember(name)(collection.addOne(readQuotedValue().toString)); collection
  }
  def readNextStringArrayInto[T <:mutable.Growable[String]](collection: T): T = {
    foreachInNextArray(collection.addOne(readQuotedValue().toString)); collection
  }
  def readCurrentStringArrayInto[T <:mutable.Growable[String]](collection: T): T = {
    foreachElementInCurrentArray(collection.addOne(quotedValue.toString)); collection
  }
  def readCurrentStringArray(): Seq[String] = {
    val buf = mutable.Buffer.empty[String]
    foreachElementInCurrentArray(buf.addOne(quotedValue.toString))
    buf.toSeq
  }
  def readCurrentInternedStringArray(): Seq[String] = {
    val buf = mutable.Buffer.empty[String]
    foreachElementInCurrentArray(buf.addOne(quotedValue.intern))
    buf.toSeq
  }
  def readNextStringArray(): Seq[String] = {
    val buf = mutable.Buffer.empty[String]
    foreachInNextArray(buf.addOne(quotedValue.toString))
    buf.toSeq
  }

  def readOptionalStringArrayMemberInto[T <:mutable.Growable[String]](name: ByteSlice, collection: =>T): Option[T] = {
    readOptionalArrayMemberInto(name,collection){ quotedValue.toString }
  }

  //--- read object members into mutable maps, using member names as keys and returning populated map

  def readNextObjectMemberInto[V,C <:mutable.Growable[(String,V)]](name: ByteSlice, collection: C)(f: =>(String,V)): C = {
    foreachInNextObjectMember(name) {
      val (k,v) = f
      collection.addOne(k -> v)
    }
    collection
  }

  def readOptionalObjectMemberInto[V,C <:mutable.Growable[(String,V)]](name: ByteSlice, collection: =>C)(f: =>(String,V)): Option[C] = {
    val ps = new ParserState

    if (!isLevelEnd && isNextObjectStartMember(name)) {
      val c = collection
      foreachInCurrentObject {
        val (k,v) = f
        c.addOne(k -> v)
      }
      Some(c)

    } else {
      ps.restore()
      None
    }
  }

  def readSomeNextObjectMemberInto[K,V,C <:mutable.Growable[(K,V)]](name: ByteSlice, collection: C)(f: =>Option[(K,V)]): C = {
    foreachInNextObjectMember(name) {
      f match {
        case Some((k,v)) => collection.addOne(k -> v)
        case None => // skip
      }
    }
    collection
  }

  def readNextObjectInto[K,V,C <:mutable.Growable[(K,V)]](collection: C)(f: =>(K,V)): C = {
    foreachInNextObject {
      val (k,v) = f
      collection.addOne(k -> v)
    }
    collection
  }

  def readSomeNextObjectInto[K,V,C <:mutable.Growable[(K,V)]](collection: C)(f: =>Option[(K,V)]): C = {
    foreachInNextObject {
      f match {
        case Some((k,v)) => collection.addOne(k -> v)
        case None => // skip
      }
    }
    collection
  }
  def readCurrentObjectInto[K,V,C <:mutable.Growable[(K,V)]](collection: C)(f: =>(K,V)): C = {
    foreachInCurrentObject {
      val (k,v) = f
      collection.addOne(k -> v)
    }
    collection
  }
  def readSomeCurrentObjectInto[K,V,C <:mutable.Growable[(K,V)]](collection: C)(f: =>Option[(K,V)]): C = {
    foreachInCurrentObject {
      f match {
        case Some((k,v)) => collection.addOne(k -> v)
        case None => // skip
      }
    }
    collection
  }


  //--- high level interface - parse everything into a JsonValue tree (AST)

  def parseJsonValue: Option[JsonValue] = {
    var top: JsonValue = null

    def _addToContainer (c: JsonContainer, v: JsonValue): Unit = {
      c match {
        case null => top = v
        case a: JsonArray => a += v
        case o: JsonObject => o += (member.toString, v)
      }
    }

    def _parseContainer (c: JsonContainer): Unit = {
      var cNext = c
      while (true) {
        readNext() match {
          case ArrayStart =>
            val v = new JsonArray
            _addToContainer(c,v)
            _parseContainer(v)

          case ObjectStart =>
            val v = new JsonObject
            _addToContainer(c,v)
            _parseContainer(v)

          case NoStart => // cannot happen - this is only an artificial construct we use for remainder parsing
          case NoEnd =>

          case _:ScalarValue =>
            if (isQuotedValue) {
              _addToContainer(c,JsonString(value.toString))
            }  else {
              if (value == nullValue) _addToContainer(c,JsonNull)
              else if (value == trueValue) _addToContainer(c,JsonBoolean(true))
              else if (value == falseValue) _addToContainer(c,JsonBoolean(false))
              else _addToContainer(c, if (value.contains('.')) JsonDouble(value.toDouble) else JsonLong(value.toLong))
              // TODO - check for NumberFormatExceptions
            }

          case NoValue | ArrayEnd | ObjectEnd => return
        }
      }
    }

    _parseContainer(null)
    Option(top)
  }

  //--- skip over content

  // this consumes the end delimiter
  def skipPastAggregate(): Unit = {
    skipToEndOfCurrentLevel()
    readNext() // we know it's the right delimiter
  }

  // note this does NOT consume the end delimiter
  def skipToEndOfLevel (lvl0: Int): Unit = {
    val curLevel = env.size
    var lvl = if (isLevelStart) curLevel-1 else curLevel
    val tgtLevel = lvl0 // if (isLevelStart) lvl0+1 else lvl0
    var i = idx

    while (true) {
      if (data(i) == '"') i = skipToEndOfString(i+1)
      if (i < limit) {
        val b = data(i)
        (b: @switch) match {
          case '{' | '[' =>
            lvl += 1
          case '}' | ']' =>
            if (lvl < curLevel) { // if we go below where we started we have to clean up env and path
              if (b == '}' && path.nonEmpty) path.pop()
              state = popEnv()
            }
            lvl -= 1

            if (lvl < tgtLevel) {
              idx = i
              return
            }
          case _ =>
        }
        i += 1
      } else { idx = i; return }
    }
  }

  @inline def skipToEndOfCurrentLevel() = skipToEndOfLevel(env.size)

  def readTextOfCurrentLevel(): String = {
    val i0 = idx
    skipToEndOfCurrentLevel()
    new String(data, i0, idx-i0)
  }

  @inline def skipToEndOfParentLevel(): Unit = {
    if (level > 0) skipToEndOfLevel(level-1) else skipToEndOfLevel(level)
  }

  def skipInCurrentLevel (nValues: Int): Unit = {
    var nRemaining = nValues

    while (nRemaining > 0 && !isLevelEnd) {
      readNext() match {
        case _:ScalarValue =>
          nRemaining -= 1
        case _:AggregateStart =>
          nRemaining -= 1
          skipPastAggregate()
        case _ =>
      }
    }
  }

  //--- internal methods

  final protected def popEnv(): State = {
    env.pop()
    if (env.isEmpty) endState else env.top
  }

  @inline final protected def checkMember (name: ByteSlice): Unit = {
    if (member != name) throw exception(s"expected member '$name' got '$member'")
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

  protected def clear(): Unit = {
    idx = 0
    limit = 0
    path.clear()
    env.clear()
    member.clear()
    value.clear()
    state = null
    lastResult = NoValue
  }

  protected def clearAll(): Unit = {
    clear()
    setData(Array.empty[Byte])
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

  protected def seekStart(): Int = {
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
      println()
    }

    matchNext {
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
      readNext() match {
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

        case NoStart => // ignore, this is just an artificial construct to support remainder parsing
        case NoEnd =>

        case v:ScalarValue =>
          printIndent
          printMember
          if (isQuotedValue) ps.print(s""""$value"""") else ps.print(value)
          if (aggregateHasMoreValues) ps.println(',') else ps.println

        case NoValue => return
      }
    }
  }

  def parseNextMemberOrElse[T](default: => Option[T])(pf: PartialFunction[ByteSlice,Option[T]])(implicit ps: ParserState = new ParserState): Option[T] = {
    if (isInObject) {
      readMemberName()
      pf.applyOrElse(member, (_:ByteSlice) => { ps.restore(); default })

    } else throw exception("not in object")
  }

  /**
    * evaluate the provided PF for the next member name within the current object
    * if the PF is not defined for the next member name this backtracks and returns None
    *
    * this is useful to process "{ msgType: ... }" messages without throwing exceptions if there is no match
    *
    * note the parser has to be in an object (i.e. surrounding "{..}" has to be parsed by caller)
    */
  def parseNextMember[T](pf: PartialFunction[ByteSlice,Option[T]])(implicit ps: ParserState = new ParserState): Option[T] = {
    parseNextMemberOrElse(None)(pf)(ps)
  }
}

//--- concrete JsonPullParser classes (although most are further derived from some of these)

/**
  * unbuffered JsonPullParser processing String input
  */
class StringJsonPullParser extends JsonPullParser {
  def initialize (s: String): Boolean = {
    clear()
    setData(s.getBytes)

    idx = seekStart()
    idx >= 0
  }

  def parseMessageOrElse[T](msg: String, default: => Option[T])(pf: PartialFunction[ByteSlice,Option[T]]): Option[T] = {
    if (initialize(msg)) {
      implicit val ps = new ParserState
      readNextObject(super.parseNextMemberOrElse(default)(pf))
    } else {
      warning(s"parser did not initialize for '${dataContext(0,20)}'")
      default
    }
  }

  def parseMessage[T] (msg: String)(pf: PartialFunction[ByteSlice,Option[T]]): Option[T] = {
    parseMessageOrElse(msg,None)(pf)
  }
}

/**
  * buffered JsonPullParser processing String input
  */
class BufferedStringJsonPullParser (initBufSize: Int = 8192) extends StringJsonPullParser {

  protected val bb = new Utf8Buffer(initBufSize)

  override def initialize (s: String): Boolean = {
    bb.encode(s)
    clear()
    setData(bb.data, bb.len)

    idx = seekStart()
    idx >= 0
  }
}

/**
  * buffered JsonPullParser processing ASCII String input
  */
class BufferedASCIIStringJsonPullParser (initBufSize: Int = 8192) extends StringJsonPullParser {

  protected val bb = new AsciiBuffer(initBufSize)

  override def initialize (s: String): Boolean = {
    bb.encode(s)
    clear()
    setData(bb.data, bb.len)

    idx = seekStart()
    idx >= 0
  }
}

/**
  * unbuffered JsonPullParser processing utf-8 byte array input
  */
class UTF8JsonPullParser extends JsonPullParser {
  def initialize (bs: Array[Byte], limit: Int): Boolean = {
    clear()
    setData(bs,limit)

    idx = seekStart()
    idx >= 0
  }

  def initialize (bs: Array[Byte]): Boolean = initialize(bs,bs.length)

  def parseMessageOrElse[T](msg: Array[Byte], default: =>Option[T])(pf: PartialFunction[ByteSlice,Option[T]]): Option[T] = {
    if (initialize(msg)) {
      implicit val ps = new ParserState
      readNextObject( super.parseNextMemberOrElse(default)(pf))
    } else {
      warning(s"parser did not initialize for '${dataContext(0,20)}'")
      default
    }
  }

  def parseMessage[T] (msg: Array[Byte])(pf: PartialFunction[ByteSlice,Option[T]]): Option[T] = {
    parseMessageOrElse(msg,None)(pf)
  }
}
