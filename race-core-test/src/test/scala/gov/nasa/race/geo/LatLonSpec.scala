package gov.nasa.race.geo

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle.Degrees
import org.scalacheck.Gen
import org.scalatest.FlatSpec

class LatLonSpec extends FlatSpec with RaceSpec {

  final val eps = 0.000001
  final val nSuccessful = 100

  //--- continental US
  val positions: Gen[GeoPosition] = for {
    φ <- Gen.choose(10.0, 60.0)
    λ <- Gen.choose(-140.0, -40.0)
  } yield GeoPosition(Degrees(φ), Degrees(λ))

  "LatLon values" should "reproduce lat/lon degrees within NAS to 6 digits" in {
    forAll (positions, minSuccessful(nSuccessful)) { p =>
      val lat = p.φ
      val lon = p.λ

      val latLon = LatLon(lat,lon)

      val llat = latLon.lat
      val llon = latLon.lon

      val dlat = lat - llat
      val dlon = lon - llon

      println(f"lat: ${lat.toDegrees}%.7f - ${llat.toDegrees}%.7f -> e: ${dlat.toDegrees}%.7f,   lon: ${lon.toDegrees}%12.7f - ${llon.toDegrees}%12.7f -> e: ${dlon.toDegrees}%.7f")

      assert(Math.abs(dlat.toDegrees) < eps)
      assert(Math.abs(dlon.toDegrees) < eps)
    }

    println(s"passed $nSuccessful tests")
  }
}
