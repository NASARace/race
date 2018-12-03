package gov.nasa.race.track

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle
import org.scalatest.FlatSpec

class CompressedTrackHistoryTraceSpec extends FlatSpec with RaceSpec {

  "a CompressedTrackHistoryTrace" should "store positions with 4-5 digits, velocities with 4 digits and heading with 2" in {

    val data = Array(
      (0, 37.62000, -122.38000, 3000.0, -60.10, 320.1230, -10.1234),
      (1, 37.62001, -122.38001, 3001.0, -50.11, 320.1231, -10.1234),
      (2, 37.62002, -122.38002, 3002.0, -40.12, 320.1232, -10.1234),
      (3, 37.62003, -122.38003, 3003.0, -30.13, 320.1233, -10.1234),
      (4, 37.62004, -122.38004, 3004.0, -20.14, 320.1234, -10.1234),
      (5, 37.62005, -122.38005, 3005.0, -10.15, 320.1235, -10.1234),
      (6, 37.62006, -122.38006, 3006.0,   0.16, 320.1236, -10.1234),
      (7, 37.62007, -122.38007, 3007.0,  10.17, 320.1237, -10.1234),
      (8, 37.62008, -122.38008, 3008.0,  20.18, 320.1238, -10.1234),
    )

    val t = new CompressedTrackHistoryTrace(5)

    assert(t.isEmpty)
    for (d <- data) t.addPre(d._1, d._2, d._3, d._4, d._5, d._6, d._7)
    assert(t.size == t.capacity)

    println("--- saturated CompressedTrackHistoryTrace in reverse order of entry:")
    var i = data.length-1
    t.foreachPreReverse { (_, t, lat, lon, alt, hdg, spd, vr) =>
      println(f"$i: $t = ($lat%10.5f, $lon%10.5f, $alt%5.0f, hdg=$hdg%7.2f, spd=$spd%10.4f, vr=$vr%10.4f)")
      assert( t == data(i)._1)
      lat shouldBe( data(i)._2 +- 0.00001)
      lon shouldBe( data(i)._3 +- 0.00001)
      alt shouldBe( data(i)._4 +- 0.5)
      hdg shouldBe( Angle.normalizeDegrees(data(i)._5) +- 0.01)
      spd shouldBe( data(i)._6 +- 0.0001)
      vr shouldBe( data(i)._7 +- 0.0001)
      i -= 1
    }
  }
}

