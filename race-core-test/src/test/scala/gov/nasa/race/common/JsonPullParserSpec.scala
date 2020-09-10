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

import gov.nasa.race.common.ConstAsciiSlice._
import gov.nasa.race.common.JsonValueConverters._
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer

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

  //--- low level (stream) parsing

  "a JsonPullParser" should "support low level stream parsing" in {
    import JsonPullParser._

    val s = """ { "foo": 42, "bar" : { "f1": "fortytwo", "f2": 42.0  , "f3": { "baz": 42.42, "faz": [1,{"a":1 , "b":"boo" },3] } } } """

    println()
    println(s"-- parsing as stream: '$s'")

    val p = new StringJsonPullParser
    assert(p.initialize(s))

    //            readNext(),       env depth,  member,  value
    p.assertNext(ObjectStart,       1,   "",      ""         )
    p.assertNext(UnQuotedValue,     1,   "foo",   "42"       )
    p.assertNext(ObjectStart,       2,   "bar",   ""         )
    p.assertNext( QuotedValue     , 2,   "f1"   , "fortytwo" )
    p.assertNext( UnQuotedValue   , 2,   "f2"   , "42.0"     )
    p.assertNext( ObjectStart     , 3,   "f3"   , ""         )
    p.assertNext( UnQuotedValue   , 3,   "baz"  , "42.42"    )
    p.assertNext( ArrayStart      , 4,   "faz"  , ""         )
    p.assertNext( UnQuotedValue   , 4,    ""    , "1"        )
    p.assertNext( ObjectStart     , 5,    ""    , ""         )
    p.assertNext( UnQuotedValue   , 5,    "a"   , "1"        )
    p.assertNext( QuotedValue     , 5,    "b"   , "boo"      )
    p.assertNext( ObjectEnd       , 4,    ""    , ""         )
    p.assertNext( UnQuotedValue   , 4,    ""    , "3"        )
    p.assertNext( ArrayEnd        , 3,    ""    , ""         )
    p.assertNext( ObjectEnd       , 2,    ""    , ""         )
    p.assertNext( ObjectEnd       , 1,    ""    , ""         )
    p.assertNext( ObjectEnd       , 0,    ""    , ""         )
    p.assertNext( NoValue         , 0,    ""    ,   ""       )
  }

  //--- derived mid-level parser example


  // the Scala objects we parse into
  class Bar (val baz: Double) {
    override def toString: String = s"Bar{$baz}"
  }
  class MyObject (val foo: Int, val bars: Seq[Bar]) {
    override def toString: String = s"MyObject{$foo,[${bars.mkString(",")}]}"
  }

  // the parser creating these objects
  class MyObjectParser extends StringJsonPullParser {
    import JsonPullParser._

    val _foo_ = asc("foo")
    val _bar_ = asc("bar")
    val _baz_ = asc("baz")

    def readBarObject: Bar = {
      readNextObject {
        val baz = readUnQuotedMember(_baz_).toDouble
        new Bar(baz)
      }
    }

    def readBarSeq: Seq[Bar] = {
      readNextArrayMemberInto(_bar_, new ArrayBuffer[Bar])(readBarObject).toSeq
    }

    def readMyObject: MyObject = {
      readNextObject {
        val foo = readUnQuotedMember(_foo_).toInt
        val bars = readBarSeq
        new MyObject(foo, bars)
      }
    }
  }

  "a derived JsonPullParser" should "support parsing objects in aggregate scope units" in {
    val s = """ { "foo": 42, "bar" : [{"baz": 42.42}, {"baz": 41.41}] }"""
    val p = new MyObjectParser

    println()
    println(s"-- parsing with derived mid-level parser in aggregate scope units: '$s'")
    if (p.initialize(s)) {
      println(p.readMyObject)
    }
  }

  "a JsonPullParser" should "support partial object member parsing" in {
    val _foo_ = asc("foo")
    val _fortyTwo_ = asc("fortyTwo")

    val s = """{ "foo": { "num": 1 , "who": "someFoo", "skipMe": "true" } , "fortyTwo": 42 }"""
    val p = new StringJsonPullParser

    println()
    println(s"-- parsing 'foo' object up to member 'skipMe' in: '$s':")
    if (p.initialize(s)){
      p.processNextObject {
        p.foreachInNextObjectMember(_foo_) {
          p.readNext()
          if (p.isScalarValue) {
            println(s"foo.${p.member} = ${p.value}")

            if (p.member =:= "num") {  // slice comparison would be more efficient
              assert(p.value.toInt == 1)
            }
            if (p.member =:= "who") {
              assert(p.value =:= "someFoo")
              p.skipToEndOfCurrentLevel()
            }
          } else fail(s"not a scalar value: '${p.member}'")

          assert(p.member != "skipMe")
        }

        val v = p.readUnQuotedMember(_fortyTwo_).toInt
        println(s"${p.member} = ${p.value}")
        assert( v == 42)
      }
    }
  }

  "a JsonPullParser" should "support slicing" in {
    val s = """ [ [-1,1,-3,",",{"a": 0, "b": 0}], [-2,2,-3,",",{"a": 0, "b": 0},42] ]  """
    val p = new StringJsonPullParser

    def readPartialArray: Int = {
      var res: Int = 0
      p.processNextArray {
        p.skipInCurrentLevel(1)
        res = p.readUnQuotedValue().toInt
        p.skipToEndOfCurrentLevel()
      }
      res
    }

    println()
    println(s"-- parsing slices from nested array: '$s'")

    println("-- 2nd value of each sub-array:")
    var n = 0
    if (p.initialize(s)) {
      p.foreachInNextArray {
        val x = readPartialArray
        println(x)

        if (n == 0) assert(x == 1)
        else if (n == 1) assert(x == 2)
        else fail(s"wrong number of extracted values: $n")
        n += 1
      }
    }
    assert(n == 2)

    println("-- 6th value of 2nd sub-array:")
    n = 0
    if (p.initialize(s)) {
      p.foreachInNextArray {
        p.processNextArray {
          if (n == 0) {
            p.skipToEndOfCurrentLevel() // skip the first subarray altogether

          } else if (n == 1) {
            p.skipInCurrentLevel(5)
            val x = p.readUnQuotedValue().toInt
            println(x)
            assert(x == 42)
            p.skipToEndOfCurrentLevel()
          } else {
            fail(s"wrong number of extracted values: $n")
          }
          n += 1
        }
      }
    }
    assert(n == 2)
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

    println()
    println(s"-- parsing JsonValue object from: '$sIn'")
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


  "a JsonPullParser" should "support mapping arrays" in {
    val _providers_ = asc("providers")
    val _id_ = asc("id")
    val _info_ = asc("info")

    case class Provider (id: String, info: String)

    val s =
      """
        |{
        |  "providers": [
        |    { "id": "provider_1", "info": "this is provider 1" },
        |    { "id": "provider_2", "info": "this is provider 2" }
        |  ]
        |}
        |""".stripMargin

    val p = new StringJsonPullParser
    p.initialize(s)

    println(s"-- mapping array from: \n$s")
    val providers = p.readNextObject {
      p.readNextArrayMemberInto(_providers_,new ArrayBuffer[Provider]) {
        p.readNextObject {
          val id = p.readQuotedMember(_id_).toString
          val info = p.readQuotedMember(_info_).toString
          println(s"  id:'$id', info:'$info'")

          Provider(id,info)
        }
      }
    }

    assert(providers.size == 2)
    assert(providers(0).id == "provider_1")
    assert(providers(1).id == "provider_2")
  }

  "a JsonPullParser" should "support enumerating object fields" in {
    val s =
      """
        |{
        |  "fieldValues": {
        |    "/cat_A/field_1": 11,
        |    "/cat_A/field_2": 12
        |  }
        |}
        |""".stripMargin

    val p = new StringJsonPullParser
    p.initialize(s)

    println(s"-- mapping object from: \n$s")

    var n = 0
    val a = Array(0,0)

    p.processNextObject {
      p.foreachInNextObjectMember(asc("fieldValues")) {
        val v = p.readUnQuotedValue().toInt
        val id = p.member.toString

        a(n) = v
        n += 1
        println(s"  '$id': $v")
      }
    }

    assert(n == 2)
    assert(a(0) == 11)
    assert(a(1) == 12)
  }


  //--------------------------

  type PermSpec = (String,String)
  type Perms = Seq[PermSpec]
  case class UserPermissions (rev: Int, users: immutable.Map[String,Perms])

  class UserPermissionsParser extends UTF8JsonPullParser {
    private val _rev_ = asc("rev")
    private val _users_ = asc("users")
    private val _providerPattern_ = asc("providerPattern")
    private val _fieldPattern_ = asc("fieldPattern")

    def parse (buf: Array[Byte]): UserPermissions = {
      initialize(buf)

      ensureNextIsObjectStart()
      val rev = readUnQuotedMember(_rev_).toInt
      val users = readNextObjectMemberInto[Perms,mutable.Map[String,Perms]](_users_,mutable.Map.empty){
        val user = readArrayMemberName().toString
        val perms = readCurrentArrayInto(ArrayBuffer.empty[PermSpec]) {
          readNextObject {
            val providerPattern = readQuotedMember(_providerPattern_).toString
            val fieldPattern = readQuotedMember(_fieldPattern_).toString
            (providerPattern, fieldPattern)
          }
        }.toSeq
        (user,perms)
      }.toMap

      UserPermissions(rev,users)
    }
  }

  "a JsonPullParser" should "map nested objects and arrays into application objects" in {
    val s =
      """
        |{
        |  "rev": 1,
        |  "users": {
        |    "gonzo": [
        |      { "providerPattern": "provider_2", "fieldPattern": ".*" },
        |      { "providerPattern": "provider_4", "fieldPattern": "/cat_A/field_1" }
        |    ],
        |    "kasimir": [
        |      { "providerPattern": "provider_(1|2|3)", "fieldPattern": "/cat_B/field_2" }
        |    ]
        |  }
        |}
        |""".stripMargin

    println()
    println(s"-- translating nested objects and arrays in $s")
    val p = new UserPermissionsParser
    val res = p.parse(s.getBytes)
    println(res)
    assert(res.users.size == 2)
  }

  //--- optional member parsing

  "a JsonPullParser" should "support optional members" in {
    val input =
      """
         {
           "title": "Sample Data Set",
           "fields": [
              { "id": "/cat_A", "attrs": ["blah"], "formula": {"src": "(sum `/cat_A/.+`)" } },
              { "id": "/cat_A/field_1"}
           ]
         }"""


    println(s"\n#-- parsing optional members in:\n$input")

    var nFields = 0

    val p = new StringJsonPullParser
    p.initialize(input)
    p.readNextObject {
      val title = p.readQuotedMember(asc("title")).toString
      p.foreachInNextArrayMember(asc("fields")) {
        val field = p.readNextObject {
          val id = p.readQuotedMember(asc("id")).toString
          val attrs = p.readOptionalStringArrayMemberInto(asc("attrs"),ArrayBuffer.empty[String]).map(_.toSeq).getOrElse(Seq.empty[String])
          if (nFields == 0) assert (attrs.nonEmpty) else assert (attrs.isEmpty)

          val min = p.readOptionalUnQuotedMember(asc("min")).map(_.toInt)
          assert(!min.isDefined)

          val formula = p.readOptionalObjectMember(asc("formula")) {
            val src = p.readQuotedMember(asc("src")).toString
            val site = p.readOptionalQuotedMember(asc("site"))
            (src,site)
          }
          if (nFields == 0) assert (formula.isDefined) else assert (!formula.isDefined)
          (id,attrs,min,formula)
        }
        nFields += 1
        println(s"field = $field")
      }
    }

    assert (nFields == 2)
  }

  "a JsonPullParser" should "parse empty objects" in {
    val input =
      """
         { "foo":{} ,
           "bar": 42
         }
      """
    println(s"\n#-- parsing empty object member in:\n$input")

    val p = new StringJsonPullParser
    p.initialize(input)
    p.readNextObject {
      p.foreachInNextObjectMember(asc("foo")) {
        fail("this should not be executed - object has no members!")
      }
      assert( p.readUnQuotedMember(asc("bar")).toInt == 42)
    }
    println("Ok.")
  }

  "a JsonPullParser" should "parse empty arrays" in {
    val input =
      """
         { "foo": [ ],"bar": 42 }
      """
    println(s"\n#-- parsing empty array member in:\n$input")

    val p = new StringJsonPullParser
    p.initialize(input)
    p.readNextObject {
      p.foreachInNextArrayMember(asc("foo")) {
        fail("this should not be executed - array has no elements!")
      }
      assert( p.readUnQuotedMember(asc("bar")).toInt == 42)
    }
    println("Ok.")
  }
}
