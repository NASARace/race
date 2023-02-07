package gov.nasa.race.geo

import gov.nasa.race.common.squared
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Angle.Degrees
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec

import scala.math.sqrt

/**
 * regression tests for Datum (coordinate conversion)
 */
class DatumSpec extends AnyFlatSpec with RaceSpec {

  final val eps = 1.0e-10

  def degError (pos0: GeoPosition, pos1: GeoPosition): Double = {
    sqrt( squared(Angle.absDiff(pos0.lat, pos1.lat).toDegrees) + squared(Angle.absDiff(pos0.lon, pos1.lon).toDegrees))
  }

  "Datum" should "have bounded wgs84 -> ecef -> wgs84 roundtrip conversion errors" in {
    val pos0 = GeoPosition.fromDegrees(37.527123, -121.404628)
    val xyz = Datum.wgs84ToECEF(pos0)
    val pos1 = Datum.ecefToWGS84(xyz)

    println("--- roundtrip conversion errors")
    println(s"wgs84: $pos0")
    println(s"ecef:  $xyz")
    println(s"wgs84: $pos1")

    val xyzErr = xyz.distanceTo(XyzPos.fromMeters(-2639039.765, -4322655.998, 3863951.903))
    val geoErr = degError(pos0,pos1)

    println(s" -> roundtrip-error: $geoErr, xyz-error: $xyzErr")

    assert( xyzErr.toMeters < 1)
    assert( geoErr < eps)
  }

  "Datum" should "convert positions in US with bounded roundtrip errors" in {
    var t, t0: Long = 0
    var n = 0

    println("--- bulk roundtrip conversion errors/time")

    val positions: Gen[GeoPosition] = for {
      φ <- Gen.choose(10.0, 60.0)
      λ <- Gen.choose(-140.0, -40.0)
    } yield GeoPosition(Degrees(φ), Degrees(λ))

    forAll (positions, minSuccessful(100000)) { pos =>
      t0 = System.nanoTime

      val xyz = Datum.wgs84ToECEF(pos)
      val pos1 = Datum.ecefToWGS84(xyz)

      t += System.nanoTime - t0

      val geoErr = degError(pos,pos1)
      assert( geoErr < eps)
      n += 1
    }

    println(s"$n successful roundtrips in ${t/1e6}ms")
  }
}
