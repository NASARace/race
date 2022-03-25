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
import gov.nasa.race.uom.{Angle, Length, Speed}
import gov.nasa.race.uom.DateTime


object ProximityEvent {
  final val ProxNew  = TrackedObject.NewFlag
  final val ProxChange  = TrackedObject.ChangedFlag
  final val ProxDrop = TrackedObject.DroppedFlag
  final val ProxCollision = 0x10000
  // ..and potentially more to follow (threat level etc.)

  def flagDescription(flags: Int): String = {
    val sb = new StringBuilder('{')
    if ((flags & ProxNew) != 0){
      sb.append("new")
    }
    if ((flags & ProxChange) != 0) {
      if (sb.length > 1) sb.append(',')
      sb.append("change")
    }
    if ((flags & ProxDrop) != 0){
      if (sb.length > 1) sb.append(',')
      sb.append("drop")
    }
    if ((flags & ProxCollision) != 0){
      if (sb.length > 1) sb.append(',')
      sb.append("collision")
    }
    sb.append('}')
    sb.toString
  }
}

/**
  * the reference objects we want to get proximities for. This can be either a dynamic or static location
  */
case class ProximityReference (id: String,
                               date: DateTime,
                               position: GeoPosition) extends TrackPoint {
  def this (ref: TrackedObjectEstimator, date: DateTime) = this(ref.track.cs, date, ref.estimatedPosition)
}

/**
  * the object to report proximity changes
  *
  * note that ProximityEvents are also implementing TrackedObject themselves
  */
case class ProximityEvent (id: String,
                           eventType: String,
                           ref: ProximityReference,
                           distance: Length,
                           status: Int,
                           track: Tracked3dObject) extends Tracked3dObject {
  import ProximityEvent._

  override def toString = f"ProximityEvent(ref=${ref.id},track=${track.cs},dist=${distance.toMeters}%.0f m,flags=${flagDescription(status)}"

  override def isNew = (status & ProxNew) != 0
  def isCollision = (status & ProxCollision) != 0

  //--- TrackedObject interface
  def cs = id  // should be named 'gid'
  def position = ref.position
  def altitude = ref.position.altitude
  def date = ref.date

  // those are not defined
  def heading = Angle.UndefinedAngle
  def speed = Speed.UndefinedSpeed
  def vr = Speed.UndefinedSpeed
}
