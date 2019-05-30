/*
 * Copyright (c) 2016, United States Government, as represented by the
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
 * limitations under the License.
 */

package gov.nasa.race.air.translator

import java.lang.Double.isFinite

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.AsdexTrack
import gov.nasa.race.config._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.track.TrackedObject.ChangedCS
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import org.joda.time.DateTime

import scala.collection.mutable.{HashMap => MutHashMap}

/**
  * translator for SWIM ASDE-X asdexMsg messages to full AsdexTracks
  *
  * NOTE - asde-x track updates come in full- and delta- positionReports. This is the stateful translator version
  * that caches records in order to make sure we don't loose AsdexTrack values for delta-reports
  *
  * Tracks are cached on a per-airport basis, i.e. by changing the airport we reset the cache. The cache is also
  * time-stamped so that we can clear it if it has expired (no updates within configurable duration). This also avoids
  * loosing information for intermittent use (e.g. when zooming in/out of an airport in the viewer)
  *
  * This implementation makes heavy use of special values (null, NaN) for optional fields and hence is not very
  * scalatic in order to avoid heap pressure (this is a high volume data stream)
  */
class AsdexMsg2FullAsdexTracks(config: Config=NoConfig) extends AsdexMsg2AsdexTracks(config) {

  //--- our cache to accumulate track infos over full/delta reports

  // TODO - this is not thread safe, turn lastTracks into a function argument
  var lastAirport: String = null // currently cached airport
  var lastTracks = new MutHashMap[String,AsdexTrack]

  override protected def setAirport (airportId: String) = {
    if (airportId != lastAirport) {
      lastAirport = airportId
      lastTracks.clear
    }
    airportId
  }

  @inline def fromDouble[A](d: Double, f: Double=>A, g: AsdexTrack=>A, fallback: A)(implicit last: Option[AsdexTrack]): A = {
    if (isFinite(d)) f(d) else withSomeOrElse(last,fallback)(g)
  }
  @inline def fromString[A](s: String, f: String=>A, g: AsdexTrack=>A, fallback: A)(implicit last: Option[AsdexTrack]): A = {
    if (s != null) f(s) else withSomeOrElse(last, fallback)(g)
  }

  // here we use the previously accumulated info to turn delta reports into full reports
  override protected def createTrack (airport: String, trackId: String, acId: String,
                                      latDeg: Double, lonDeg: Double,
                                      altFt: Double, spdMph: Double, hdgDeg: Double, vertRate: Double,
                                      date: DateTime, status: Int, acType: String): AsdexTrack = {
    implicit val last = lastTracks.get(trackId)

    // if input values are defined, use those. Otherwise use the last value or the fallback if there was none
    val lat = fromDouble(latDeg, Degrees, _.position.φ, UndefinedAngle)
    val lon = fromDouble(lonDeg, Degrees, _.position.λ, UndefinedAngle)
    val alt = fromDouble(altFt, Feet, _.position.altitude, UndefinedLength)
    val hdg = fromDouble(hdgDeg, Degrees, _.heading, UndefinedAngle)
    val spd = fromDouble(spdMph, UsMilesPerHour, _.speed, UndefinedSpeed)
    val vr = fromDouble(vertRate, FeetPerMinute, _.vr, UndefinedSpeed)
    val cs = fromString(acId, getCallsign(_,trackId), _.cs, trackId)
    val act = fromString(acType, Some(_), _.acType, None)
    var statusFlags = status
    var changedCS: Option[ChangedCS] = None

    ifSome(lastTracks.get(trackId)) { lastTrack =>
      if (acId != null && lastTrack.cs != cs) {
        statusFlags |= TrackedObject.ChangedCSFlag
        changedCS = Some(ChangedCS(lastTrack.cs))
      }
    }

    val track = new AsdexTrack(trackId, cs, GeoPosition(lat,lon,alt), spd, hdg, vr, date, statusFlags, airport, act)
    ifSome(changedCS) { track.amend }

    if (track.isDropped) {
      lastTracks -= trackId
    } else {
      lastTracks += trackId -> track
    }

    if (track.position.isDefined) track else null
  }
}

/**
  * an AsdexMsg3FullAsdexTracks translator that does not reset track maps when switching
  * between airports
  * Use this variant if we need to process several airports simultaneously
  */
class AllAsdexMsg2FullAsdexTracks(config: Config=NoConfig) extends AsdexMsg2FullAsdexTracks(config) {

  val airports = new MutHashMap[String,MutHashMap[String,AsdexTrack]]

  override protected def setAirport (airportId: String) = {
    if (airportId != lastAirport) {
      lastAirport = airportId
      lastTracks = airports.getOrElseUpdate(airportId, new MutHashMap[String,AsdexTrack])
    }
    airportId
  }
}