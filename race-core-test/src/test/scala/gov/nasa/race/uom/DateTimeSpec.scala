package gov.nasa.race.uom

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import java.time.{ZonedDateTime}

/**
  * regression test for DateTimeSpec
  *
  * TODO - needs a lot more tests
  */
class DateTimeSpec extends AnyFlatSpec with RaceSpec {

  "DateTime" should "parse to the same epoch ms values as java.time" in {
    val specs = Array(
      "2019-07-19T11:53:04Z",   // no sec fraction
      "2019-07-19T11:53:04.42Z", // sec fraction
      "2019-07-19T11:53:04.123-07:00", // only offset
      "2019-07-19T11:53:04.123-09:00[US/Pacific]" // both zone id and offset, offset ignored
    )

    specs.foreach { spec =>
      println(s"--- $spec")

      val a = DateTime.parseYMDT(spec)
      val msa = a.toEpochMillis

      val b = ZonedDateTime.parse(spec)
      val msb = b.toInstant.toEpochMilli

      println(s"  dt  = $msa : $a")
      println(s"  zdt = $msb : $b")

      msa shouldBe msb
    }
  }
}
