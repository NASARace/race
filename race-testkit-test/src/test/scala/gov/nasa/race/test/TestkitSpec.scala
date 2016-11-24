package gov.nasa.race.test

import org.scalatest.FlatSpec
import java.util.{List =>JList}

/**
  * test suite for RACE test infrastructure
  */
class TestkitSpec  extends FlatSpec with RaceSpec {

  "a RACE test" should "find qualified resource files" in {
    val rf = qualifiedResourceFile("testResource")
    println(s"    found resourceFile: $rf")
  }

  "a RACE test" should "find base resource files" in {
    val rf = baseResourceFile("testConfig.conf")
    println(s"    found resourceFile: $rf")
  }

  "a RACE test" should "load base configs from resource files" in {
    val conf = baseResourceConfig("testConfig.conf")

    val actors: JList[_] = conf.getObjectList("universe.actors")
    if (actors.size == 2) println(s"    found 2 elements in 'universe.actors': $actors")
    else fail("missing or wrong config object 'universe.actors'")

    val internalUri = conf.getString("universe.activemq.export.internal-uri")
    if (internalUri == "vm://localhost") println(s"    found universe.activemq.export.internal-uri = $internalUri")
    else fail("missing or wrong config value for universe.activemq.export.internal-uri")
  }

  "a RACE test" should "load qualified configs from resource files" in {
    val conf = qualifiedResourceConfig("amq.conf")
    val internalUri = conf.getString("activemq.export.internal-uri")
    if (internalUri == "vm://localhost") println(s"    found activemq.export.internal-uri = $internalUri")
    else fail("missing or wrong config value for activemq.export.internal-uri")
  }
}
