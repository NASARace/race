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

import gov.nasa.race.common.JsonValueConverters._
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for JsonPullParser
  */
class JsonPullParserSpec extends AnyFlatSpec with RaceSpec {

  "a JsonPullParser" should "correctly printout Json source" in {
    val p = new StringJsonPullParser

    val s1 = """ { "foo": 42, "bar" : { "boo": "fortytwo"}} """
    val s2 = """ { "foo": 42, "bar" : [ "fortytwo", 42.0, { "baz": 42.42, "fop":[1,2,3]}]} """
    val s3 = """ { "foo": 42, "bar" : { "f1": "fortytwo", "f2": 42.0, "f3": { "baz": 42.42 } } } """
    val inputs = Array(s1,s2,s3)

    for (s <- inputs) {
      println(s"--- input: '$s'")
      p.initialize(s)
      p.printOn(System.out)
    }
  }

  //--- specialized parser example

  class Bar (val baz: Double) {
    override def toString: String = s"Bar{$baz}"
  }
  class MyObject (val foo: Int, val bars: Seq[Bar]) {
    override def toString: String = s"MyObject{$foo,[${bars.mkString(",")}]}"
  }

  class MyObjectParser extends StringJsonPullParser {
    import JsonPullParser._

    val _foo_ = ConstAsciiSlice("foo")
    val _bar_ = ConstAsciiSlice("bar")
    val _baz_ = ConstAsciiSlice("baz")

    def readBarObject: Bar = {
      var baz = 0.0
      readObject {
        baz = readUnQuotedMember(_baz_).toDouble
      }
      new Bar(baz)
    }

    def readBarSeq: Seq[Bar] = {
      var list = Seq.empty[Bar]
      readMemberArray(_bar_) {
        list = list :+ readBarObject
      }
      list
    }

    def readMyObject: MyObject = {
      if (parseNextValue != ObjectStart) throw new JsonParseException("not an object value")

      val foo = readUnQuotedMember(_foo_).toInt
      val bars = readBarSeq

      val res = parseNextValue
      if (res != ObjectEnd) throw new JsonParseException(s"unexpected members in object: $res")
      new MyObject(foo, bars)
    }
  }

  "a JsonPullParser" should "support parsing objects in lexical scopes" in {
    val s = """ { "foo": 42, "bar" : [{"baz": 42.42}, {"baz": 41.41}]} """
    val p = new MyObjectParser

    println
    println(s"-- parsing: '$s'")
    if (p.initialize(s)) {
      println(p.readMyObject)
    }
  }

  "a JsonPullParser" should "support slicing" in {
    val s = """ [ [-1,1,-3,",",{"a": 0, "b": 0}], [-2,2,-3,",",{"a": 0, "b": 0}] ]  """
    val p = new MyObjectParser

    def readPartialArray: Int = {
      p.matchArrayStart
      p.skip(1)
      val res = p.readUnQuotedValue.toInt
      p.skipToEndOfCurrentLevel
      p.matchArrayEnd
      res
    }

    println
    println(s"-- parsing: '$s'")

    println("-- 2nd value of each sub-array:")
    var n = 0
    if (p.initialize(s)) {
      p.readArray {
        val x = readPartialArray
        println(x)

        if (n == 0) assert(x == 1)
        else if (n == 1) assert(x == 2)
        else fail(s"wrong number of extracted values: $n")
        n += 1
      }
    }

    println("-- 1st value of 2nd sub-array:")
    n = 0
    if (p.initialize(s)) {
      p.readArray {
        p.matchArrayStart // each element is a sub-array
        if (n == 0) {
          p.skipToEndOfCurrentLevel
        }
        else if (n == 1) {
          val x = p.readUnQuotedValue.toInt
          println(x)
          assert(x == -2)
          p.skipToEndOfCurrentLevel
        } else {
          fail(s"wrong number of extracted values: $n")
        }
        n += 1
        p.matchArrayEnd
      }
    }
  }

  "a JsonPullParser" should "support object member parsing" in {
    val _foo_ = ConstAsciiSlice("foo")
    val _fortyTwo_ = ConstAsciiSlice("fortyTwo")
    val _who_ = ConstAsciiSlice("who")

    val s = """{ "foo": { "num": 1, "who": "someFoo", "skipMe": "true" }, "fortyTwo": 42 }"""
    val p = new StringJsonPullParser

    println
    println(s"-- parsing 'foo' object up to member 'skipMe' in: '$s':")
    if (p.initialize(s)){
      p.matchObjectStart
      assert (p.parseMembersOfObject(_foo_) { res =>
        if (res.isScalarValue) {
          println(s"foo.${p.member} = ${p.value}")

          if (p.member =:= "num") {
            assert(p.value.toInt == 1)
          }
          if (p.member =:= "who") {
            assert(p.value =:= "someFoo")
            p.skipToEndOfCurrentLevel
          }
        }
      })

      assert(p.parseScalarMember(_fortyTwo_))
    }
  }

  "a JsonPullParser" should "support parsing into generic JsonValue structure" in {
    val jsonIn = JsonObject(
      "one"->JsonDouble(42.1),
      "two"->JsonArray(1,2,3),
      "three"->JsonObject(
        "a"->1,
        "b"->2
      ),
      "four"->JsonString("whatever")
    )
    val sIn = jsonIn.toString

    println
    println(s"-- parsing json object '$sIn'")
    val p = new StringJsonPullParser
    p.initialize(sIn)

    p.parseJsonValue match {
      case Some(jsonOut) =>
        val sOut = jsonOut.toString
        println(s"  -> $sOut")
        assert( sOut == sIn)
      case None => fail("failed to parse")
    }
  }
}
