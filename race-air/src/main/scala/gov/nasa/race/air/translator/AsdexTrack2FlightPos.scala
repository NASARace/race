/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limit
 */
package gov.nasa.race.air.translator

import com.typesafe.config.Config
import gov.nasa.race.air.{Airport, AsdexTrack, AsdexTracks, FlightPos}
import gov.nasa.race.config._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GreatCircle
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed
import gov.nasa.race.uom.Speed._

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * translator from airborne asde-x tracks to FlightPos objects
  *
  * note that altitude and speed are optional. We only consider the track airborne if there is altitude information,
  * but we also get this for surface tracks so the actor needs to be configured with a airborne-threshold.
  * We also get airborne asde-x tracks that don't have speed, in which case we extrapolate from the last two positions
  *
  * Since AsdexTracks currently don't have an airport reference, this mean can't use this translator for
  * different airports
  *
  * TODO - we should periodically purge old tracks from the 'lastTracks' cache
  * TODO - this is going away once AsdexTrack and FlightPos are merged
  */
class AsdexTrack2FlightPos (val config: Config=NoConfig) extends ConfigurableTranslator {

  override def flatten = true // report as single flight objects

  val airborneThreshold = Feet(config.getIntOrElse("airborne-ft", 50))
  val dropAfter = config.getFiniteDurationOrElse("drop-after", 10.seconds).toMillis
  val addAltitude = Feet(config.getDoubleOrElse("add-altitude",0)) // optional addition to make it appear on top

  var lastAirport: String = null
  var airborneAltitude = Length0
  val lastTracks = mutable.HashMap[String,AsdexTrack]()

  override def translate(src: Any): Option[Any] = {
    src match {
      case asdexTracks: AsdexTracks =>
        if (checkAirport(asdexTracks)) {
          val flights = asdexTracks.tracks.flatMap(translateTrack)
          if (flights.isEmpty) None else Some(flights)
        } else None
      case _ => None
    }
  }

  def checkAirport (asdexTracks: AsdexTracks): Boolean = {
    if (asdexTracks.airport == lastAirport) {
      true
    } else {
      Airport.asdexAirports.get(asdexTracks.airport) match {
        case Some(airport) =>
          lastAirport = airport.id
          airborneAltitude = airport.elevation + airborneThreshold
          lastTracks.clear
          true
        case None => false
      }
    }
  }

  def translateTrack (track: AsdexTrack): Option[FlightPos] = {
    val alt = track.altitude
    if (alt.isDefined) {
      if (alt >= airborneAltitude) {
        var spd = track.speed
        if (spd.isDefined) {
          lastTracks -= track.id
        } else {
          spd = estimateSpeed(track)
        }

        val hdg = track.heading
        if (hdg.isDefined) {
          Some(FlightPos(track.id, track.cs, track.position, alt, spd, hdg, track.vr, track.date))
        } else None

      } else None // too low
    } else None // no defined altitude
  }

  def estimateSpeed (track: AsdexTrack): Speed = {
    val lt = lastTracks.get(track.id)
    lastTracks += track.id -> track

    lt match {
      case Some(lastTrack) =>
        val dt = track.date.getMillis - lastTrack.date.getMillis
        if (dt < dropAfter) {
          val d = GreatCircle.distance(lastTrack.position, track.position)
          GreatCircle.distance(lastTrack.position, track.position) / Duration(dt,MILLISECONDS)
        } else {
          Knots(100) // assumed to be a new track
        }
      case None =>
        Knots(100) // new track, just a very rough guess - we should get the next track in a second
    }
  }
}
