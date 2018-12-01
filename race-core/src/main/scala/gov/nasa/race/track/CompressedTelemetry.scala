package gov.nasa.race.track

import gov.nasa.race.geo.WGS84Codec

trait CompressedTelemetry extends Telemetry {
  protected var data: Array[Long]  // provided by concrete type

  protected var posCodec = new WGS84Codec // needs to be our own object to avoid result allocation
  protected var t0Millis: Long = 0        // start time in epoch millis

  protected def setTrackData(idx: Int, t: Long, lat: Double, lon: Double, alt: Double, hdg: Double, spd: Double, vr: Double): Unit = {
    val dtMillis = t - t0Millis
    val latlon = posCodec.encode(lat,lon)
    val altCm = Math.round(alt * 100.0).toInt

    data(idx) = latlon
    data(idx+1) = (dtMillis << 32) | altCm
    
  }

  protected def processTrackData(i: Int, idx: Int, f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit = {
    posCodec.decode(data(idx))
    val w = data(idx+1)
    val t = t0Millis + (w >> 32)
    val altMeters = (w & 0xffffffff).toInt / 100.0
    //f(i, t, posCodec.latDeg, posCodec.lonDeg, altMeters)
  }
}
