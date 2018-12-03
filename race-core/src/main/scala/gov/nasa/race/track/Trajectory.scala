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

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import org.joda.time.DateTime

/**
  * abstraction for trajectories that does not imply underlying representation, allowing for memory
  * optimized implementations
  */
trait Trajectory {
  def capacity: Int
  def size: Int

  def addPre(t: Long, lat: Double, lon: Double, alt: Double): Trajectory // low level add to avoid temporary objects
  def add(date: DateTime, lat: Angle, lon: Angle, alt: Length): Trajectory = {
    addPre(date.getMillis, lat.toDegrees, lon.toDegrees, alt.toMeters)
  }

  /** low level iteration support that does not require temporary objects for FlightPath elements
    * The provided function takes 5 arguments:
    *   Int - path element index
    *   Double,Double - lat,lon in Degrees
    *   Double - alt in meters
    *   Long - epoch millis
    */
  def foreachPre(f: (Int,Long,Double,Double,Double)=>Unit): Unit
  def foreach(g: (Int, DateTime, Angle,Angle,Length)=>Unit): Unit = {
    foreachPre((i:Int, t:Long, latDeg:Double, lonDeg:Double, altM:Double) => {
      g(i,new DateTime(t),Degrees(latDeg),Degrees(lonDeg),Meters(altM))
    })
  }

  /**
    * iterator in LIFO order
    */
  def foreachPreReverse(f: (Int,Long,Double,Double,Double)=>Unit): Unit
  def foreachReverse(g: (Int, DateTime, Angle,Angle,Length)=>Unit): Unit = {
    foreachPreReverse((i:Int, t:Long, latDeg:Double, lonDeg:Double, altM:Double) => {
      g(i,new DateTime(t),Degrees(latDeg),Degrees(lonDeg),Meters(altM))
    })
  }
  //--- methods that can be overridden but have a generic implementation

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size > 0

  def add (e: TrackPoint): Trajectory = {
    val pos = e.position
    addPre(e.date.getMillis, pos.φ.toDegrees, pos.λ.toDegrees, pos.altitude.toMeters)
  }
  def += (tp: TrackPoint) = add(tp)

  def addAll (tps: Seq[TrackPoint]): Unit = tps.foreach(add)  // can be overridden for more efficient version
  def ++= (tps: Seq[TrackPoint]): Unit = addAll(tps)


  //--- retrieve snapshot as arrays of times positions

  def getPositionsPre: (Array[Long],Array[Double],Array[Double], Array[Double]) = {
    val ts = new Array[Long](size)
    val lats = new Array[Double](size)
    val lons = new Array[Double](size)
    val alts = new Array[Double](size)

    foreachPre { (i, t, lat, lon, alt) =>
      ts(i) = t
      lats(i) = lat
      lons(i) = lon
      alts(i) = alt
    }

    (ts,lats,lons,alts)
  }

  def getPositions: Array[(DateTime,GeoPosition)] = {
    val a = new Array[(DateTime,GeoPosition)](size)
    foreachPre { (i, t, lat, lon, alt) =>
      a(i) = (new DateTime(t), GeoPosition.fromDegreesAndMeters(lat,lon,alt))
    }
    a
  }
}

object EmptyTrajectory extends Trajectory {
  override def capacity = 0
  override def size = 0
  override def addPre(t: Long, lat: Double, lon: Double, alt: Double): Trajectory = new CompressedTrackPath().addPre(t, lat, lon, alt)
  override def add(tp: TrackPoint) = new CompressedTrackPath().add(tp)
  override def foreachPre(f: (Int,Long,Double,Double,Double) => Unit): Unit = {}
  override def foreachPreReverse(f: (Int,Long,Double,Double,Double) => Unit) = {}
}