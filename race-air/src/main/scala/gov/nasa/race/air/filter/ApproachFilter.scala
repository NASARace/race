package gov.nasa.race.air.filter

import com.typesafe.config.Config
import gov.nasa.race.air.{Airport, TrackedAircraft}
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.geo.{Datum, GeoPosition}
import gov.nasa.race.uom.{Angle, Length, Speed}

/**
  * a high volume filter to detect aircraft that could be approach candidates
  */
class ApproachFilter (val center: GeoPosition, val radius: Length,
                      val minSlope: Angle, val maxSlope: Angle, val maxSpeed: Speed, maxDeviation: Angle,
                      val config: Config=null) extends ConfigurableFilter {

  def this (conf: Config) = this(Airport.allAirports(conf.getString("airport")).position,
    conf.getLengthOrElse("radius", NauticalMiles(80)),
    conf.getAngleOrElse("min-slope", Degrees(1.5)),
    conf.getAngleOrElse("max-slope", Degrees(15)),
    conf.getSpeedOrElse( key = "max-speed", Knots(300)),
    conf.getAngleOrElse("max-deviation", Degrees(90)),
    conf)


  override def pass (o: Any): Boolean = {
    o match {
      case ac: TrackedAircraft => isApproachCandidate(ac)
      case _ => false
    }
  }

  def isApproachCandidate (ac: TrackedAircraft): Boolean = {
    val acPos = ac.position
    val d = Datum.meanEuclidean2dDistance(center,acPos)
    if (d < radius){                                      // near enough
      val bearing = Datum.euclideanHeading(acPos, center)
      if (absDiff(bearing,ac.heading) < maxDeviation){    // not moving away
        val h = acPos.altitude - center.altitude
        val gs = Radians(Math.atan(h/d))
        if (gs > minSlope && gs < maxSlope) {             // in glide slope range
          if (ac.speed < maxSpeed) {                      // not too fast
            return true
          }
        }
      }
    }
    false
  }
}
