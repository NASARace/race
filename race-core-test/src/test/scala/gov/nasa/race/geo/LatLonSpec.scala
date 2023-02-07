package gov.nasa.race.geo

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Angle.Degrees
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec

class LatLonSpec extends AnyFlatSpec with RaceSpec {

  @inline def round_7(d: Double): Double = Math.round(d * 10000000.0) / 10000000.0

  final val eps = 0.0000001
  final val nSuccessful = 10000

  //--- continental US with 7 digits precision (SFDPS: 6, ADS-B: 5)
  val positions: Gen[GeoPosition] = for {
    φ <- Gen.choose(10.0, 60.0)
    λ <- Gen.choose(-140.0, -40.0)
  } yield GeoPosition(Degrees(round_7(φ)), Degrees(round_7(λ)))

  "LatLon values" should "reproduce lat/lon degrees within NAS to 6 digits" in {
    println("----------------- test LatLon accuracy")
    var avgAbsLatErr: Double = 0.0
    var avgAbsLonErr: Double = 0.0
    var t, t0: Long = 0

    forAll (positions, minSuccessful(nSuccessful)) { p =>
      val lat = p.φ
      val lon = p.λ

      t0 = System.nanoTime
      val latLon = LatLon(lat,lon)  // encode

      val llat = latLon.lat         // decode
      val llon = latLon.lon         //  -"-
      t += System.nanoTime - t0

      val dlat = Angle.absDiff(lat, llat)
      val dlon = Angle.absDiff(lon, llon)

      avgAbsLatErr += dlat.toDegrees
      avgAbsLonErr += dlon.toDegrees

      //println(f"lat: ${lat.toDegrees}%.7f - ${llat.toDegrees}%.7f -> e: ${dlat.toDegrees}%+.8f,   lon: ${lon.toDegrees}%12.7f - ${llon.toDegrees}%12.7f -> e: ${dlon.toDegrees}%+.8f")

      assert(dlat.toDegrees < eps)
      assert(dlon.toDegrees < eps)
    }

    avgAbsLatErr /= nSuccessful
    avgAbsLonErr /= nSuccessful

    println(s"passed $nSuccessful tests: avg errors = ($avgAbsLatErr,$avgAbsLonErr), t=$t")
  }
}
