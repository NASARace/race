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

trait LinearCompressedTrajectory extends CompressedTrajectory {
  override protected[track] var data: Array[Long] = _             // initialized on demand

  protected[track] var _size = 0
  def size = _size

  override def capacity: Int = if (data != null) data.length / 2 else 0

  override def foreachPre(f: (Int,Long,Double,Double,Double)=>Unit): Unit = {
    @tailrec def _processEntry (i: Int, f: (Int,Long,Double,Double,Double)=>Unit): Unit = {
      if (i < _size) {
        processTrackPointData(i,i,f)
        _processEntry(i+1,f)
      }
    }
    _processEntry(0,f)
  }

  override def foreachPreReverse(f: (Int,Long,Double,Double,Double)=>Unit): Unit = {
    @tailrec def _processEntry (i: Int, f: (Int,Long,Double,Double,Double)=>Unit): Unit = {
      if (i >= 0) {
        processTrackPointData(i,i,f)
        _processEntry(i-1,f)
      }
    }
    _processEntry(_size-1,f)
  }
}


class FixedCompressedTrajectory extends LinearCompressedTrajectory {

  override def snapshot: Trajectory = this // no need to snap, we are invariant

  /**
    * return a new Trajectory object that can be modified
    * use this for branching trajectories
    */
  override def branch: ModifiableTrajectory = {
    val t = new ModifiableCompressedTrajectory
    t.data = data.clone()
    t._size = _size
    t
  }
}

/**
  * a LinearCompressedTrajectory that can be modified
  */
class ModifiableCompressedTrajectory(capacityIncrement: Int=32)
             extends LinearCompressedTrajectory with ModifiableTrajectory {
  private var growthCycle = 0
  final val linearGrowthCycles = 5 // once that is exceeded we grow exponentially

  protected def growDataCapacity = {
    growthCycle += 1
    val newCapacity = if (growthCycle > linearGrowthCycles) data.length * 2 else data.length + capacityIncrement*2
    val newData = new Array[Long](newCapacity)
    data.copyToArray(newData,0,data.length)
    data = newData
  }

  override def capacity = if (data != null) data.length / 2 else capacityIncrement


  /**
    * low level add that avoids temporary objects
    * NOTE - caller has to ensure proper units of measure
    *
    * @param lat latitude in degrees
    * @param lon longitude in degrees
    * @param alt altitude in meters (or NaN if undefined)
    * @param t epoch value (or 0 if undefined)
    */
  override def addPre(t: Long, lat: Double, lon: Double, alt: Double): ModifiableTrajectory = {
    val i = _size

    if (i == 0) {
      data = new Array[Long](capacityIncrement*2)
    } else if (i*2 == data.length) {
      growDataCapacity
    }

    setTrackPointData(i, t, lat,lon,alt)

    _size += 1
    this
  }


  override def snapshot: Trajectory = {
    val t = new FixedCompressedTrajectory
    t.data = data.clone()
    t._size = _size
    t
  }

  override def branch: ModifiableTrajectory = {
    val t = new ModifiableCompressedTrajectory(capacityIncrement)
    t.data = data.clone()
    t._size = _size
    t
  }
}
