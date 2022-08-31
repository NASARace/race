package gov.nasa.race.earth

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import gov.nasa.race.whileSome
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for GpxParser
  */
class GpxParserSpec extends AnyFlatSpec with RaceSpec {

  "a GpxParser" should "parse a known *.gpx file" in {
    val parser = new GpxParser
    val input = FileUtils.fileContentsAsBytes(baseResourceFile("test.gpx")).get
    var n = 0

    if (parser.parseHeader(input)) {
      if (parser.parseToNextTrack()) {
        whileSome(parser.parseNextTrackPoint()) { gps=>
          println(gps)
          n += 1
        }
        assert(n == 7)
      } else fail("failed to parse trk")
    } else fail("failed to parse gpx header")
  }
}
