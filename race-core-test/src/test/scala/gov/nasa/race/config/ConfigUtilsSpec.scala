package gov.nasa.race.config

import ConfigUtils._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

class ConfigUtilsSpec extends AnyFlatSpec with RaceSpec {
  val eps = 0.00000001

  "ConfigUtils" should "parse optional length units" in {

    def check (test: String, expected: Length, sVal: String): Unit = {
      println(s"--- test $test")
      val conf = createConfig(s"len = $sVal")
      val len = conf.getLength("len")
      println(s"len = $len")
      len.toMeters should be(expected.toMeters +- eps)
    }

    check("plain double", Meters(1.23), "1.23")
    check("plain int", Meters(123), "123")
    check("meters", Meters(3000), "3000.0m")
    check("nautical miles", NauticalMiles(42), "42nm")
    check("feet", Feet(4200), "4200ft")

    check("exponential meters", Meters(1.2e+5), "1.2e+5m")
  }
}
