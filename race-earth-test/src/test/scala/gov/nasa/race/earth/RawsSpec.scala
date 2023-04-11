package gov.nasa.race.earth

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Temperature.Fahrenheit
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
 * reg tests for Raws functions
 */
class RawsSpec extends AnyFlatSpec with RaceSpec {

  "a RawsParser" should "read the last record of wx_station files" in {
    val parser = new RawsParser
    val input = FileUtils.fileContentsAsBytes(baseResourceFile("wx_station-BNDC1-20230411-t0755z.csv")).get

    parser.getLastBasicRecord(input) match {
      case Some(rec) =>
        println(s"temp=${rec.temp.toFahrenheit},spd=${rec.windSpeed.toUsMilesPerHour},dir=${rec.windDirection.toDegrees}")
        assert( rec.temp == Fahrenheit(45.0))
      case None => fail("failed to parse")
    }
  }
}
