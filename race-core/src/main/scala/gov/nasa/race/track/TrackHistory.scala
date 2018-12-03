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
 * limitations under the License.
 */
package gov.nasa.race.track

import gov.nasa.race.uom._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import org.joda.time.DateTime

/**
  * object encapsulating history of dynamic states of a TrackedObject (position,heading,velocities)
  */
trait TrackHistory {
  def capacity: Int
  def size: Int

  def addPre(t: Long, lat: Double, lon: Double, alt: Double, hdg: Double, spd: Double, vr: Double): TrackHistory // low level add to avoid temporary objects
  def add(date: DateTime, lat: Angle, lon: Angle, alt: Length, hdg: Angle, spd: Speed, vr: Speed): TrackHistory = {
    addPre(date.getMillis, lat.toDegrees, lon.toDegrees, alt.toMeters, hdg.toDegrees, spd.toMetersPerSecond, vr.toMetersPerSecond)
  }

  /** low level iteration support that does not require temporary objects for FlightPath elements
    * The provided function takes 5 arguments:
    *   Int - path element index
    *   Double,Double - lat,lon in Degrees
    *   Double - alt in meters
    *   Long - epoch millis
    */
  def foreachPre(f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit
  final def foreach(g: (Int, DateTime, Angle,Angle,Length,Angle,Speed,Speed)=>Unit): Unit = {
    foreachPre((i:Int, t:Long, latDeg:Double, lonDeg:Double, altM:Double, hdgDeg:Double,spdMs:Double,vrMs:Double) => {
      g(i,new DateTime(t),Degrees(latDeg),Degrees(lonDeg),Meters(altM),Degrees(hdgDeg),MetersPerSecond(spdMs),MetersPerSecond(vrMs))
    })
  }

  /**
    * iterator in LIFO order
    */
  def foreachPreReverse(f: (Int,Long,Double,Double,Double,Double,Double,Double)=>Unit): Unit
  final def foreachReverse(g: (Int, DateTime, Angle,Angle,Length,Angle,Speed,Speed)=>Unit): Unit = {
    foreachPreReverse((i:Int, t:Long, latDeg:Double, lonDeg:Double, altM:Double, hdgDeg:Double,spdMs:Double,vrMs:Double) => {
      g(i,new DateTime(t),Degrees(latDeg),Degrees(lonDeg),Meters(altM),Degrees(hdgDeg),MetersPerSecond(spdMs),MetersPerSecond(vrMs))
    })
  }
  //--- methods that can be overridden but have a generic implementation

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size > 0

  def add (e: TrackedObject): TrackHistory = {
    val pos = e.position
    addPre(e.date.getMillis, pos.φ.toDegrees, pos.λ.toDegrees, pos.altitude.toMeters,
           e.heading.toDegrees,e.speed.toMetersPerSecond, e.vr.toMetersPerSecond)
  }
  def += (to: TrackedObject) = add(to)
}
