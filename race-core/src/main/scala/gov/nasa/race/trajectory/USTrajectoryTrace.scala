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
package gov.nasa.race.trajectory

import gov.nasa.race.common.CircularSeq
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.TrackPoint
import gov.nasa.race.uom.{Angle, AngleArray, Length, LengthArray, TimeArray}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import org.joda.time.DateTime


object USTrajectoryTrace {

  // geo center of contiguous US
  val USCenterLat = 39.833333
  val USCenterLon = -98.583333
}
import USTrajectoryTrace._

/**
  * a partial trajectory that stores N last track points in circular buffers
  *
  * note that we only store relative 32bit quantities, hence USTrajectoryTraces should only be used
  * for locations within the US and durations <900h.
  *
  * for locations within the continental US the positional error due to truncation is <1m, which
  * is below the accuracy of single frequency GPS (~2m URE according to FAA data)
  */
class USTrajectoryTrace (val capacity: Int) extends MutTrajectory with CircularSeq {

  var t0Millis: Long = -1 // start time of trajectory in epoch millis (set when entering first point)

  val ts: Array[Int] = new Array(capacity)
  val lats: Array[Float] = new Array(capacity)
  val lons: Array[Float] = new Array(capacity)
  val alts: Array[Float] = new Array(capacity)

  val cleanUp = None // no need to clean up dropped track points since we don't store objects

  override def getTime(i: Int): Long = ts((tail+i)%capacity) + t0Millis

  protected def getTrackPoint (i: Int): TrackPoint = {
    val pos = new LatLonPos(Degrees(lats(i) + USCenterLat), Degrees(lons(i) + USCenterLon), Meters(alts(i)))
    new TrajectoryPoint(new DateTime(ts(i).toLong + t0Millis), pos)
  }

  protected def updateMutTrackPoint (p: MutTrajectoryPoint) (i: Int): TrackPoint = {
    p.update(ts(i).toLong + t0Millis, Degrees(lats(i) + USCenterLat), Degrees(lons(i) + USCenterLon), Meters(alts(i)))
  }

  override def append (millis: Long, lat: Angle, lon: Angle, alt: Length): Unit = {
    if (t0Millis < 0) t0Millis = millis

    val i = incIndices
    ts(i) = (millis - t0Millis).toInt
    lats(i) = (lat.toDegrees - USCenterLat).toFloat
    lons(i) = (lon.toDegrees - USCenterLon).toFloat
    alts(i) = alt.toMeters.toFloat
  }

  override def apply (off: Int): TrackPoint = {
    if (off >= _size) throw new ArrayIndexOutOfBoundsException(off)
    getTrackPoint(storeIdx(off))
  }

  override def getDataPoint (off: Int, p: TDP3): TDP3 = {
    val i = storeIdx(off)
    p.update(ts(i) + t0Millis, lats(i) + USCenterLat, lons(i) + USCenterLon, alts(i))
  }

  override def iterator: Iterator[TrackPoint] = new ForwardIterator(getTrackPoint)

  override def iterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = new ForwardIterator(updateMutTrackPoint(p))


  override def reverseIterator: Iterator[TrackPoint] = new ReverseIterator(getTrackPoint)

  override def reverseIterator(p: MutTrajectoryPoint): Iterator[TrackPoint] = new ReverseIterator(updateMutTrackPoint(p))


  override def dropWhile(p: TrackPoint => Boolean): Unit = ???


  override def copyToArrays(t: TimeArray, lat: AngleArray, lon: AngleArray, alt: LengthArray): Unit = ???

  override def snapshot: Trajectory = ???

  override def branch: MutTrajectory = ???
}
