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
package gov.nasa.race.common

import gov.nasa.race.common.ImplicitGenerics._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.DateTime
import gov.nasa.race.yieldAfter

import java.text.NumberFormat
import scala.collection.mutable.ArrayBuffer

/**
  * something that can serialize to JSON
  */
trait JsonSerializable {

  // the minimum that needs to be provided
  def serializeMembersTo (writer: JsonWriter): Unit

  // override if there is specific formatting to be used for fiel values
  def serializeMembersFormattedTo (writer: JsonWriter): Unit = serializeMembersTo(writer)

  def serializeTo (writer: JsonWriter): Unit = {
    writer.beginObject
    serializeMembersTo(writer)
    writer.endObject
  }

  def serializeFormattedTo (writer: JsonWriter): Unit = {
    writer.beginObject
    serializeMembersFormattedTo(writer)
    writer.endObject
  }

  def serializeFormattedAs (writer: JsonWriter, key: String): Unit = {
    writer.writeMemberName(key)
    serializeFormattedTo(writer)
  }

  def serializeAs (writer: JsonWriter, key: String): Unit = {
    writer.writeMemberName(key)
    serializeTo(writer)
  }
}

/**
  * a JsonSerializable that only writes message objects, i.e. needs to clear the writer
  */
trait JsonMessageObject extends JsonSerializable {
  override def serializeTo (writer: JsonWriter): Unit = {
    writer.clear()
    super.serializeTo(writer)
  }
}

object JsonWriter {
  sealed trait ElementType
  object ObjectType extends ElementType
  object ArrayType extends ElementType
  object RawElements extends ElementType // no automatic toplevel brackets

  def forObject: JsonWriter = new JsonWriter(ObjectType)
  def forArray: JsonWriter = new JsonWriter(ArrayType)
  def forElements: JsonWriter = new JsonWriter(RawElements)

  def toJsonString(s: String) = s.replace("\"","\\\"")
}
import JsonWriter._


/**
  * a simple lightweight JSON formatter - not as comprehensive as libraries such as Lift-JSON, Play or Gson.
  * the main idea is to favor control over what is written instead of automatic serialization of Scala objects
  * (based on reflection), with the assumption that Json is mostly composed from slices of multiple objects.
  *
  * subclass to support specific object values
  *
  * this only produces compact JSON as the main use case is to serialize network data
  *
  * Note there are some syntax checks (e.g. writeMember only in object environment) but by using the low level API it
  * is still possible to produce invalid JSON
  *
  * Note that instances are not supposed to be thread safe
  *
  * TODO - add formatting option
  */
class JsonWriter (jsonType: ElementType = JsonWriter.RawElements, initSize: Int = 4096) {

  protected val buf = new StringBuilder(initSize)
  protected var needsSep: Boolean = false

  protected val elementStack: ArrayBuffer[ElementType] = ArrayBuffer.empty

  protected var pretty: Boolean = false // format with indented lines
  protected var useReadableDateTime: Boolean = false // write DateTime as readable string

  initialize

  protected def initialize: Unit = {
    needsSep = false
    buf.clear()
    elementStack.clear()

    jsonType match {
      case JsonWriter.ObjectType => beginObject
      case JsonWriter.ArrayType => beginArray
      case JsonWriter.RawElements => // no toplevel delimiters added
    }
  }

  def format (printFormatted: Boolean): Boolean = {
    val prev = pretty
    pretty = printFormatted
    prev
  }
  def readableDateTime (printReadable: Boolean) : Boolean = {
    val prev = useReadableDateTime
    useReadableDateTime = true
    prev
  }

  protected final def indent: Unit = {
    var i = 0
    while (i < elementStack.size) { buf.append("  "); i += 1 }
  }
  @inline protected final def newLine: Unit = {
    if (pretty) {
      buf.append("\n")
      indent
    }
  }

  @inline protected final def checkAndArmSeparator: Unit = {
    if (needsSep) buf.append(',') else needsSep = true
  }
  @inline protected final def checkAndDisarmSeparator: Unit = {
    if (needsSep) {
      buf.append(',')
      needsSep = false
    }
  }
  @inline protected final def checkSeparator: Unit = {
    if (needsSep) buf.append(',')
  }
  @inline protected final def armSeparator: JsonWriter = {
    needsSep = true
    this
  }
  @inline protected final def disarmSeparator: JsonWriter = {
    needsSep = false
    this
  }

  def length: Int = buf.length
  def clear(): JsonWriter = { initialize; this }

  def writeln(): Unit = buf.append("\n") // for explicit formatting

  @inline final def push (t: ElementType): Unit = {
    elementStack += t
  }

  @inline final def pop(t: ElementType): Unit = {
    if (elementStack.isEmpty || (elementStack.last ne t)) {
      throw new RuntimeException(s"malformed JSON nesting: '${buf.toString}'")
    } else {
      elementStack.remove(elementStack.length-1)
    }
  }

  def closeOpenEnvironments: Unit = {
    for (t <- elementStack.reverseIterator) {
      t match {
        case ObjectType => endObject
        case ArrayType => endArray
        case RawElements => // that would be an error
      }
    }
  }

  @inline final def checkEnvironment(t: ElementType): Unit = {
    if (elementStack.nonEmpty && (elementStack.last ne t)) throw new RuntimeException("invalid JSON environment")
  }

  def toJson (f: JsonWriter=>Unit): String = {
    f(this)
    toJson
  }

  def toJson (o: JsonSerializable): String = {
    o.serializeTo(this)
    toJson
  }

  def toNewJson (o: JsonSerializable): String = {
    clear()
    o.serializeTo(this)
    toJson
  }

  def toJson: String = {
    closeOpenEnvironments
    buf.toString
  }

  // for debugging purposes
  def snapshot: String = buf.toString

  @inline final def beginObject: JsonWriter = {
    checkSeparator
    buf.append('{');
    push(ObjectType)
    disarmSeparator
  }
  @inline final def endObject: JsonWriter = {
    pop(ObjectType)
    newLine
    buf.append('}');
    armSeparator
  }
  @inline def writeObject (f: JsonWriter=>Unit): JsonWriter = {
    beginObject
    f(this)
    endObject
  }

  def writeObjectMember(memberName: CharSequence,jsonSerializable: JsonSerializable): JsonWriter = {
    writeMemberName(memberName)
    beginObject
    jsonSerializable.serializeMembersTo(this)
    endObject
    this
  }
  def writeObjectMember(memberName: CharSequence)(f: JsonWriter=>Unit): JsonWriter = {
    writeMemberName(memberName)
    writeObject(f)
  }
  def writeIntMembersObject(memberName: CharSequence, it: Iterable[(String,Int)]): JsonWriter = {
    writeMemberName(memberName)
    writeIntMembers(it)
  }
  def writeLongMembersObject(memberName: CharSequence, it: Iterable[(String,Long)]): JsonWriter = {
    writeMemberName(memberName)
    writeLongMembers(it)
  }
  def writeDoubleMembersObject(memberName: CharSequence, it: Iterable[(String,Double)]): JsonWriter = {
    writeMemberName(memberName)
    writeDoubleMembers(it)
  }
  def writeStringMembersObject(memberName: CharSequence, it: Iterable[(String,String)]): JsonWriter = {
    writeMemberName(memberName)
    writeStringMembers(it)
  }


  @inline final def beginArray: JsonWriter = {
    checkSeparator
    buf.append('[');
    push(ArrayType)
    disarmSeparator
  }
  @inline final def endArray: JsonWriter = {
    pop(ArrayType)
    newLine
    buf.append(']');
    armSeparator
  }
  @inline def writeArray (f: JsonWriter=>Unit): JsonWriter = {
    beginArray
    f(this)
    endArray
  }
  def writeArrayMember(memberName: CharSequence)(f: JsonWriter=>Unit): JsonWriter = {
    writeMemberName(memberName)
    writeArray(f)
  }
  def writeStringArrayMember(memberName: CharSequence, it: Iterable[String]): JsonWriter = {
    writeMemberName(memberName)
    writeStringValues(it)
  }
  def writeIntArrayMember(memberName: CharSequence, it: Iterable[Int]): JsonWriter = {
    writeMemberName(memberName)
    writeIntValues(it)
  }
  def writeLongArrayMember(memberName: CharSequence, it: Iterable[Long]): JsonWriter = {
    writeMemberName(memberName)
    writeLongValues(it)
  }
  def writeDoubleArrayMember(memberName: CharSequence, it: Iterable[Double]): JsonWriter = {
    writeMemberName(memberName)
    writeDoubleValues(it)
  }

  def writeGeoPositionArrayMember(memberName: CharSequence, it: Iterable[GeoPosition]): JsonWriter = {
    writeMemberName(memberName)
    writeGeoPositionValues(it)
  }

  //--- basic writes

  @inline final def writeSeparator: JsonWriter = {
    if (needsSep){
      buf.append(',');
      disarmSeparator
    } else this
  }

  @inline final def writeString (s: CharSequence): JsonWriter = {
    checkAndArmSeparator
    buf.append('"')
    buf.append(s)
    buf.append('"')
    this
  }

  @inline final def writeMemberName (name: CharSequence): JsonWriter = {
    checkEnvironment(ObjectType)
    checkAndDisarmSeparator
    newLine
    buf.append('"')
    buf.append(name)
    buf.append("\":")
    this
  }

  @inline final def writeUnQuotedValue(s: CharSequence): JsonWriter = {
    checkAndArmSeparator
    buf.append(s)
    this
  }

  @inline final def writeInt (v: Int): JsonWriter = {
    checkAndArmSeparator
    buf.append(v)
    this
  }
  @inline final def writeLong (v: Long): JsonWriter = {
    checkAndArmSeparator
    buf.append(v)
    this
  }
  @inline final def writeDouble (v: Double): JsonWriter = {
    if (v.isDefined) { // NaN is not a valid JSON number
      checkAndArmSeparator
      buf.append(v)
    }
    this
  }
  @inline final def writeDouble (v: Double, fmt: String): JsonWriter = {
    if (v.isDefined) {
      checkAndArmSeparator
      buf.append(String.format(fmt, v));
    }
    this
  }
  @inline final def writeBoolean (v: Boolean): JsonWriter = {
    checkAndArmSeparator
    buf.append(if (v) "true" else "false")
    this
  }
  @inline final def writeNull: JsonWriter = { buf.append("null"); this }

  def writeBooleanMember (k: CharSequence, v: Boolean): JsonWriter = {
    writeMemberName(k)
    writeBoolean(v)
    armSeparator
  }

  def writeIntMember (k: CharSequence, v: Int): JsonWriter = {
    writeMemberName(k)
    writeInt(v)
    armSeparator
  }

  def writeIntMember (k: CharSequence, v: Long, fmt: NumberFormat): JsonWriter = {
    writeMemberName(k)
    writeUnQuotedValue(fmt.format(v))
    armSeparator
  }

  def writeLongMember (k: CharSequence, v: Long): JsonWriter = {
    writeMemberName(k)
    writeLong(v)
    armSeparator
  }

  def writeLongMember (k: CharSequence, v: Long, fmt: NumberFormat): JsonWriter = {
    writeMemberName(k)
    writeUnQuotedValue(fmt.format(v))
    armSeparator
  }

  def writeDoubleMember (k: CharSequence, v: Double): JsonWriter = {
    if (v.isDefined) {
      writeMemberName(k)
      writeDouble(v)
      armSeparator
    }
    this
  }

  def writeDoubleMember (k: CharSequence, v: Double, fmt: String): JsonWriter = {
    if (v.isDefined) {
      writeMemberName(k)
      writeDouble(v, fmt)
      armSeparator
    }
    this
  }

  def writeDoubleMember (k: CharSequence, v: Double, fmt: NumberFormat): JsonWriter = {
    if (v.isDefined) {
      writeMemberName(k)
      writeUnQuotedValue(fmt.format(v))
      armSeparator
    }
    this
  }

  def writeStringMember (k: CharSequence, v: String): JsonWriter = {
    writeMemberName(k)
    writeString(v)
    armSeparator
  }

  def writeUnQuotedMember (k: CharSequence, v: String): JsonWriter = {
    writeMemberName(k)
    writeUnQuotedValue(v)
    armSeparator
  }

  def writeDateTimeMember (memberName: CharSequence, dt: DateTime): JsonWriter = {
    if (useReadableDateTime) {
      writeStringMember(memberName, dt.toString)
    } else {
      writeLongMember(memberName, dt.toEpochMillis)
    }
  }

  def writeGeoPositionMembers (pos: GeoPosition): JsonWriter = {
    pos.serializeMembersTo(this)
    this
  }

  def writeMember (k: CharSequence)(f: JsonWriter=>Unit): JsonWriter = {
    writeMemberName(k)
    f(this)
    armSeparator
  }

  @inline final def += (v: String): JsonWriter = writeString(v)
  @inline final def += (v: Boolean): JsonWriter = writeBoolean(v)
  @inline final def += (v: Int): JsonWriter = writeInt(v)
  @inline final def += (v: Long): JsonWriter = writeLong(v)
  @inline final def += (v: Double): JsonWriter = writeDouble(v)

  @inline final def += (p: StringStringPair): JsonWriter = writeStringMember(p.tuple._1,p.tuple._2)
  @inline final def += (p: StringBooleanPair): JsonWriter = writeBooleanMember(p.tuple._1,p.tuple._2)
  @inline final def += (p: StringIntPair): JsonWriter = writeIntMember(p.tuple._1,p.tuple._2)
  @inline final def += (p: StringLongPair): JsonWriter = writeLongMember(p.tuple._1,p.tuple._2)
  @inline final def += (p: StringDoublePair): JsonWriter = writeDoubleMember(p.tuple._1,p.tuple._2)

  //--- AnyVal iterables

  def writeFormatted (newFormat: Boolean)(f: => Unit): JsonWriter = {
    val prev = format(false)
    f
    format(prev)
    this
  }

  def writeStringValues (it: Iterable[String]): JsonWriter = {
    writeFormatted(false) {
      beginArray
      for (v <- it) {
        writeString(v)
      }
      endArray
    }
  }

  def writeStringMembers (it: Iterable[(String,String)]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      writeString(e._2)
    }
    endObject
  }

  def writeBooleanValues (it: Iterable[Boolean]): JsonWriter = {
    writeFormatted(false) {
      beginArray
      for (v <- it) {
        writeBoolean(v)
      }
      endArray
    }
  }

  def writeBooleanMembers (it: Iterable[(String,Boolean)]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      writeBoolean(e._2)
    }
    endObject
  }

  def writeIntValues (it: Iterable[Int]): JsonWriter = {
    writeFormatted(false) {
      beginArray
      for (v <- it) {
        writeInt(v)
      }
      endArray
    }
  }

  def writeIntMembers (it: Iterable[(String,Int)]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      writeInt(e._2)
    }
    endObject
  }

  def writeLongValues (it: Iterable[Long]): JsonWriter = {
    writeFormatted(false) {
      beginArray
      for (v <- it) {
        writeLong(v)
      }
      endArray
    }
  }

  def writeLongMembers (it: Iterable[(String,Long)]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      writeLong(e._2)
    }
    endObject
  }

  def writeDoubleValues (it: Iterable[Double]): JsonWriter = {
    writeFormatted(false) {
      beginArray
      for (v <- it) {
        writeDouble(v)
      }
      endArray
    }
  }

  def writeGeoPositionValues (it: Iterable[GeoPosition]): JsonWriter = {
    writeFormatted(false) {
      beginArray
      for (pos <- it) {
        beginArray
        writeUnQuotedValue(pos.latLon_5)
        endArray
      }
      endArray
    }
  }

  def writeDoubleMembers (it: Iterable[(String,Double)]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      writeDouble(e._2)
    }
    endObject
  }

  @inline final def ++= (v: StringIterable): JsonWriter = writeStringValues(v.iterable)
  @inline final def ++= (v: BooleanIterable): JsonWriter = writeBooleanValues(v.iterable)
  @inline final def ++= (v: IntIterable): JsonWriter = writeIntValues(v.iterable)
  @inline final def ++= (v: LongIterable): JsonWriter = writeLongValues(v.iterable)
  @inline final def ++= (v: DoubleIterable): JsonWriter = writeDoubleValues(v.iterable)

  @inline final def ++= (v: StringStringPairIterable): JsonWriter = writeStringMembers(v.iterable)
  @inline final def ++= (v: StringBooleanPairIterable): JsonWriter = writeBooleanMembers(v.iterable)
  @inline final def ++= (v: StringIntPairIterable): JsonWriter = writeIntMembers(v.iterable)
  @inline final def ++= (v: StringLongPairIterable): JsonWriter = writeLongMembers(v.iterable)
  @inline final def ++= (v: StringDoublePairIterable): JsonWriter = writeDoubleMembers(v.iterable)

  //--- generic objects - implicit adapter

  // NOTE - write and writeSelf have to include proper begin/endObject calls

  @inline final def write[T](o: T)(implicit adapter: JsonAdapter[T]): JsonWriter = {
    adapter.writeJson(this,o)
    this
  }

  def writeValues[T](it: Iterable[T])(implicit adapter: JsonAdapter[T]): JsonWriter = {
    beginArray
    for (v <- it) {
      newLine
      write(v)
    }
    endArray
  }

  def writeMembers[T] (it: Iterable[(String,T)])(implicit adapter: JsonAdapter[T]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      write(e._2)
    }
    endObject
  }

  //--- generic objects of types that implement ToJson

  @inline final def writeSelf(o: ToJson): JsonWriter = {
    o.writeJson(this)
    this
  }

  def writeSelfValues (it: Iterable[ToJson]): JsonWriter = {
    beginArray
    for (v <- it){
      writeSelf(v)
    }
    endArray
  }

  def writeSelfMembers (it: Iterable[(String,ToJson)]): JsonWriter = {
    beginObject
    for (e <- it) {
      writeMemberName(e._1)
      writeSelf(e._2)
    }
    endObject
  }
}

class SyncJsonWriter (jsonType: ElementType = JsonWriter.RawElements, initSize: Int = 4096)
        extends JsonWriter(jsonType, initSize) {

  override def toJson (o: JsonSerializable): String = synchronized {
    super.toJson(o)
  }
}

/**
  * for types that know how to turn into JSON
  */
trait ToJson {
  def writeJson(writer: JsonWriter): Unit
}

/**
  * dedicated adapter object
  */
trait JsonAdapter[T] {
  def writeJson(writer: JsonWriter, obj: T): Unit
}

/**
 * something that needs a JsonWriter that is shared between traits
 * primary purpose of this type is to make the shared writer use thread safe
 *
 * NOTE - this only protects 'writer' - there still can be races in the JSON production function f
 */
trait JsonProducer {
  // make sure nobody else sees this instance as writers are not thread safe
  private val writer: JsonWriter = new JsonWriter()

  protected def toNewJson(f: JsonWriter=>Unit): String = {
    writer.synchronized {
      writer.clear()
      f(writer)
      yieldAfter(writer.toJson)(writer.clear())
    }
  }
}