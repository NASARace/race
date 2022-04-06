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

import java.io.{PrintStream, PrintWriter, StringWriter}

import scala.collection.{SeqMap, mutable}
import scala.collection.mutable.ArrayBuffer


/**
  * a converter that can translate the generic type T to a JsonValue
  */
trait ToJsonValue[T] {
  def toJsonValue(v:T): JsonValue
}

/**
  * a collection of objects that can be used as evidence parameters to convert simple types to JsonValues
  */
object JsonValueConverters {

  implicit object FromBoolean extends ToJsonValue[Boolean] {
    def toJsonValue(v:Boolean) = new JsonBoolean(v)
  }
  implicit object FromInt extends ToJsonValue[Int] {
    def toJsonValue(v:Int) = new JsonLong(v)
  }
  implicit object FromLong extends ToJsonValue[Long] {
    def toJsonValue(v:Long) = new JsonLong(v)
  }
  implicit object FromDouble extends ToJsonValue[Double] {
    def toJsonValue(v:Double) = new JsonDouble(v)
  }
  implicit object FromString extends ToJsonValue[String] {
    def toJsonValue(v:String) = new JsonString(v)
  }
}

case class JsonPrintOptions(noNullMembers: Boolean=false, pretty: Boolean = false, indent: Int = 4) {
  var level = 0

  def incLevel() = level += 1
  def decLevel() = level -= 1

  def printNewLine(w: PrintWriter): Unit = {
    w.println()
    printIndent(w)
  }

  def printIndent (w: PrintWriter): Unit = {
    var i=0;
    val n = level * indent
    while (i < n) {
      w.print(' ')
      i += 1
    }
  }
}

/**
  * type system for generic translation of Json strings
  * note we keep containers mutable so that we can also construct / generate Json strings
  */
sealed trait JsonValue {
  def printOn(w: PrintWriter, opt: JsonPrintOptions): Unit

  def printOn(ps: PrintStream, opt: JsonPrintOptions): Unit = {
    val pw = new PrintWriter(ps)
    printOn(pw, opt)
    pw.flush
  }

  override def toString: String = toFormattedString(JsonPrintOptions())

  def toFormattedString(opts: JsonPrintOptions): String = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    printOn(pw, opts)
    pw.flush()
    sw.toString
  }

  def isNull: Boolean = false

  def asJsonBoolean: JsonBoolean = throw new RuntimeException(s"not a JsonBoolean: $this")
  def asJsonString: JsonString = throw new RuntimeException(s"not a JsonString: $this")
  def asJsonNumber: JsonNumber = throw new RuntimeException(s"not a JsonNumber: $this")
  def asJsonLong: JsonLong = throw new RuntimeException(s"not a JsonLong: $this")
  def asJsonDouble: JsonDouble = throw new RuntimeException(s"not a JsonDouble: $this")
  def asJsonObject: JsonObject = throw new RuntimeException(s"not a JsonObject: $this")
  def asJsonArray: JsonArray = throw new RuntimeException(s"not a JsonArray: $this")
}

object JsonNumber {
  def apply (v: Long): JsonNumber = JsonLong(v)
  def apply (v: Double): JsonNumber = JsonDouble(v)
}

/**
  * root type for Json numbers
  */
sealed trait JsonNumber extends JsonValue {
  def asLong: Long
  def asDouble: Double

  override def asJsonNumber: JsonNumber = this
}

/**
  * root type for Json objects and arrays
  */
sealed trait JsonContainer extends JsonValue

object JsonObject {
  def apply(ms: (String,JsonValue)*): JsonObject = new JsonObject(mutable.LinkedHashMap[String,JsonValue](ms:_*))

  def apply[T](vs: (String,T)*)(implicit evidence: ToJsonValue[T]): JsonObject = {
    new JsonObject(vs.map(p=> p._1 -> evidence.toJsonValue(p._2)):_*)
  }

  def apply[T](m: SeqMap[String,T])(implicit evidence: ToJsonValue[T]): JsonObject = {
    val mm = new mutable.LinkedHashMap[String,JsonValue]
    m.foreach(e => mm.addOne(e._1 -> evidence.toJsonValue(e._2)))
    new JsonObject(mm)
  }
}

/**
  * a Json object, which is an abstraction of a mutable Map[String,JsonValue]
  */
case class JsonObject(members: mutable.Map[String, JsonValue] = mutable.LinkedHashMap.empty)
                                                  extends Iterable[(String, JsonValue)] with JsonContainer {
  def this (ms: (String,JsonValue)*) = this(mutable.LinkedHashMap[String,JsonValue](ms:_*))

  override def size: Int = members.size
  def apply(key: String): JsonValue = members(key)
  def get(key: String): Option[JsonValue] = members.get(key)
  def iterator: Iterator[(String, JsonValue)] = members.iterator
  def nonNullIterator: Iterator[(String, JsonValue)] = members.filter(e=> e._2 ne JsonNull).iterator
  def foreachMember (f: Tuple2[String,JsonValue]=>Unit): Unit = members.foreach(f)
  def foreachNonNullMember (f: Tuple2[String,JsonValue]=>Unit): Unit = members.foreach(e=> if (e._2 ne JsonNull) f(e))
  def add(kv: (String, JsonValue)): Unit = members += kv
  def +=(kv: (String, JsonValue)): Unit = members += kv

  def printOn(w: PrintWriter, opt:JsonPrintOptions): Unit = {
    var i = 0
    w.print('{')
    opt.incLevel()
    if (opt.pretty && members.nonEmpty) opt.printNewLine(w)
    members.foreach { e=>
      if (!opt.noNullMembers ||(e._2 ne JsonNull)) {
        if (i > 0) {
          w.print(',')
          if (opt.pretty) opt.printNewLine(w)
        }
        w.print('"')
        w.print(e._1)
        w.print("\":")
        e._2.printOn(w, opt)
        i += 1
      }
    }
    opt.decLevel()
    if (opt.pretty && members.nonEmpty) opt.printNewLine(w)
    w.print('}')
  }

  override def asJsonObject: JsonObject = this
}

case object JsonNull extends JsonValue {
  def printOn(w: PrintWriter,opt:JsonPrintOptions): Unit = w.print("null") // this should never print if noNullMembers

  //override def asJsonNull: JsonNull = this  // FIXME
}

object JsonArray {
  def apply(es: JsonValue*):JsonArray = JsonArray(ArrayBuffer[JsonValue](es:_*))

  def apply[T](vs: T*)(implicit evidence: ToJsonValue[T]): JsonArray = {
    new JsonArray(ArrayBuffer[JsonValue](vs.map(evidence.toJsonValue):_*))
  }
  def apply[T](a: Array[T])(implicit evidence: ToJsonValue[T]): JsonArray = {
    new JsonArray(ArrayBuffer.from[JsonValue](a.map(evidence.toJsonValue)))
  }
}

/**
  * a Json array, which is an abstraction for a mutable IndexedBuffer[JsonValue]
  */
case class JsonArray(elements: mutable.IndexedBuffer[JsonValue] = ArrayBuffer.empty)
                                                    extends Iterable[JsonValue] with JsonContainer {
  def this (es: JsonValue*) = this(ArrayBuffer[JsonValue](es:_*))

  override def size: Int = elements.size

  def apply(i: Int): JsonValue = {
    if (i < 0 || i >= elements.size) throw new ArrayIndexOutOfBoundsException(i)
    elements(i)
  }

  def elementsArray: Array[JsonValue] = elements.toArray
  def iterator: Iterator[JsonValue] = elements.iterator
  def add(elem: JsonValue): Unit = elements.addOne(elem)
  def +=(elem: JsonValue): Unit = elements.addOne(elem)

  def printOn(w: PrintWriter, opt: JsonPrintOptions): Unit = {
    var i = 0
    w.print('[')
    opt.incLevel()
    if (opt.pretty && elements.nonEmpty) opt.printNewLine(w)
    elements.foreach { e=>
      if (i > 0) {
        w.print(',')
        if (opt.pretty && !e.isInstanceOf[JsonValue]) opt.printNewLine(w)
      }
      e.printOn(w,opt)
      i += 1
    }
    opt.decLevel()
    if (opt.pretty && elements.nonEmpty) opt.printNewLine(w)
    w.print(']')
  }

  override def asJsonArray: JsonArray = this
}

/**
  * a Json string value
  */
case class JsonString(value: String) extends JsonValue {

  override def isNull: Boolean = (value == null)

  def printOn(w: PrintWriter,opt: JsonPrintOptions): Unit = {
    if (value != null) {
      w.print('"')
      w.print(value)
      w.print('"')
    } else {
      w.print("null")
    }
  }

  override def asJsonString: JsonString = this
}

case class JsonLong(value: Long) extends JsonNumber {
  def asLong: Long = value
  def asDouble: Double = value.toDouble

  def printOn(w: PrintWriter,opt: JsonPrintOptions): Unit = w.print(value)

  override def asJsonLong: JsonLong = this
}

case class JsonDouble(value: Double) extends JsonNumber {
  def asLong: Long = value.round
  def asDouble: Double = value

  def printOn(w: PrintWriter,opt: JsonPrintOptions): Unit = w.print(value)

  override def asJsonDouble: JsonDouble = this
}

case class JsonBoolean(value: Boolean) extends JsonValue {
  def printOn(w: PrintWriter,opt: JsonPrintOptions): Unit = w.print(value)

  override def asJsonBoolean: JsonBoolean = this
}

