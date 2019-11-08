package gov.nasa.race.common

import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

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

    val _foo_ = Slice("foo")
    val _bar_ = Slice("bar")
    val _baz_ = Slice("baz")

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

    println(s"parsing: '$s'")
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

    println(s"parsing: '$s'")

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
}
