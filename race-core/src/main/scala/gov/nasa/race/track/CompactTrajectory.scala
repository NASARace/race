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
package gov.nasa.race.track

import gov.nasa.race.geo.WGS84Codec

import scala.annotation.tailrec

/**
  * A FlightPath with a compact encoding
  */
class CompactTrajectory(capacityIncrement: Int=32) extends Trajectory {
  private var growthCycle = 0
  final val linearGrowthCycles = 5 // once that is exceeded we grow exponentially

  protected var posCodec = new WGS84Codec
  protected var data: Array[Long] = _             // initialized on demand
  protected var t0Millis: Long = 0                // start time in epoch millis
  protected var _size = 0

  def size = _size

  protected def growDataCapacity = {
    growthCycle += 1
    val newCapacity = if (growthCycle > linearGrowthCycles) data.length * 2 else data.length + capacityIncrement*2
    val newData = new Array[Long](newCapacity)
    data.copyToArray(newData,0,data.length)
    data = newData
  }

  override def capacity = if (data != null) data.length / 2 else capacityIncrement

  override def add (e: TrackPoint): Trajectory = {
    val pos = e.position
    add(pos.φ.toDegrees,pos.λ.toDegrees,pos.altitude.toMeters,e.date.getMillis)
  }

  /**
    * low level add that avoids temporary objects
    * NOTE - caller has to ensure proper units of measure
    *
    * @param lat latitude in degrees
    * @param lon longitude in degrees
    * @param alt altitude in meters (or NaN if undefined)
    * @param t epoch value (or 0 if undefined)
    */
  override def add (lat: Double, lon: Double, alt: Double, t: Long): Trajectory = {
    val i = _size*2

    if (i == 0) {
      data = new Array[Long](capacityIncrement*2)
      t0Millis = t
    } else if (i == data.length) {
      growDataCapacity
    }

    val dtMillis = t - t0Millis
    val latlon = posCodec.encode(lat,lon)
    val altCm = Math.round(alt * 100.0).toInt

    data(i) = latlon
    data(i+1) = (dtMillis << 32) | altCm

    _size += 1
    this
  }


  override def foreach(f: (Int,Double,Double,Double,Long)=>Unit): Unit = {
    @tailrec def _processEntry (i: Int, f: (Int,Double,Double,Double,Long)=>Unit): Unit = {
      if (i < _size) {
        val j = i*2
        posCodec.decode(data(j))
        val w = data(j+1)
        val t = t0Millis + (w >> 32)
        val altMeters = (w & 0xffffffff).toInt / 100.0
        f(i,posCodec.latDeg,posCodec.lonDeg,altMeters,t)
        _processEntry(i+1,f)
      }
    }
    _processEntry(0,f)
  }
}
