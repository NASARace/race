package gov.nasa.race.common.inlined

import gov.nasa.race.common._
import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Angle._

object LatLon {
  //--- redundant to Angle but Angle is not inlined
  @inline val Pi = Math.PI
  @inline val TwoPi = Math.PI * 2
  @inline val HalfPi = Math.PI / 2
  @inline val DegreesInRadian = Pi / 180.0

  @inline def degreesToRadians (deg: Double): Double = deg * DegreesInRadian
  @inline def radiansToDegrees (rad: Double): Double = rad / DegreesInRadian
  @inline def normalizeRadians (d: Double): Double = d - TwoPi * Math.floor((d + π) / TwoPi) // [-π..π]
  @inline def normalizeRadians2Pi (d: Double): Double = if (d<0) d % TwoPi + TwoPi else d % TwoPi  // 0..2π


  @inline final val LAT_RES: Double = 0xffffffffL.toDouble / Pi      // [0,pi] => [0,0xffffffff]
  @inline final val LON_RES: Double = 0xffffffffL.toDouble / TwoPi  // [0,2pi] => [0,0xffffffff]

  @inline def apply (lat: Angle, lon: Angle): LatLon = {
    new LatLon(encodeRadians(lat.toRadians,lon.toRadians))
  }

  @inline def fromDegrees (latDeg: Double, lonDeg: Double): LatLon = {
    new LatLon( encodeRadians(Angle.degreesToRadians(latDeg),Angle.degreesToRadians(lonDeg)))
  }

  @inline def encodeRadians (latRad: Double, lonRad: Double): Long = {
    val lat = normalizeRadians2Pi(latRad)
    val lon = normalizeRadians2Pi(lonRad)

    (lat * LAT_RES).round << 32 | (lon * LON_RES).round
  }
}
import LatLon._


class LatLon (val l: Long) extends AnyVal {

  @inline def lat: Angle = {
    val r = (l>>32)/LAT_RES
    Radians(if (r > Pi) r - Pi else r)
  }
  @inline def latDeg: Double = Math.round(lat.toDegrees * 10000000.0) / 10000000.0

  @inline def lon: Angle = {
    val r = (l & 0xffffffffL)/LON_RES
    Radians(if (r > Pi) r - LatLon.TwoPi else r)
  }
  @inline def lonDeg: Double = Math.round(lon.toDegrees * 10000000.0) / 10000000.0
}
