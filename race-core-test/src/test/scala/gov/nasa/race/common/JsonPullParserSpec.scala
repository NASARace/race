package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

class JsonPullParserSpec extends AnyFlatSpec with RaceSpec {

  "a JsonPullParser" should "correctly printout Json source" in {
    val p = new StrJsonPullParser

    val s1 = """ { "foo": 42, "bar" : { "boo": "fortytwo"}} """
    val s2 = """ { "foo": 42, "bar" : [ "fortytwo", 42.0, { "baz": 42.42, "fop":[1,2,3]}]} """
    val s3 = """ { "foo": 42, "bar" : { "f1": "fortytwo", "f2": 42.0, "f3": { "baz": 42.42 }]} """
    val inputs = Array(s1,s2,s3)

    for (s <- inputs) {
      println(s"--- input: '$s'")
      p.initialize(s)
      p.printOn(System.out)
    }
  }
}
