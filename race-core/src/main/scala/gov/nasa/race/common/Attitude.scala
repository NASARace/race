/*
 * Copyright (c) 2023, United States Government, as represented by the
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
package gov.nasa.race.common

import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Angle.Radians

/**
 * object that represents orientation in 3D space
 */
trait Attitude {
  def toEulerAngles: EulerAngles
  def toQuaternion: Quaternion
}

case class Quaternion (w: Double, qx: Double, qy: Double, qz: Double) extends Attitude {
  def toQuaternion = this

  def toEulerAngles: EulerAngles = {
    val qy2: Double = qy * qy

    val t0: Double = -(2.0) * (qy2 + qz * qz) + 1.0
    val t1: Double = +(2.0) * (qx * qy + w * qz)
    var t2: Double = -(2.0) * (qx * qz - w * qy)
    val t3: Double = +(2.0) * (qy * qz + w * qx)
    val t4: Double = -(2.0) * (qx * qx + qy2) + 1.0

    if (t2 > 1.0) t2 = 1.0
    else if (t2 < -1.0) t2 = -1.0

    val pitch = Math.asin( t2)
    val roll = Math.atan2( t3, t4)
    val yaw = Math.atan2( t1, t0)
    
    EulerAngles( Radians(pitch), Radians(roll), Radians(yaw))
  }

  //... more to follow
}

case class EulerAngles (pitch: Angle, roll: Angle, yaw: Angle) extends Attitude {
  def toEulerAngles: EulerAngles = this

  def toQuaternion: Quaternion = {
    val t0 = Math.cos( yaw.toRadians * 0.5)
    val t1 = Math.sin( yaw.toRadians * 0.5)
    val t2 = Math.cos( roll.toRadians * 0.5)
    val t3 = Math.sin( roll.toRadians * 0.5)
    val t4 = Math.cos( pitch.toRadians * 0.5)
    val t5 = Math.sin( pitch.toRadians * 0.5)

    val t24 = t2 * t4
    val t25 = t2 * t5

    val w  = t24 * t0 + t3 * t5 * t1
    val qx =  t3 * t4 * t0 - t25 * t1
    val qy = t25 * t0 + t3 * t4 * t1
    val qz = t24 * t1 - t3 * t5 * t0

    Quaternion( w, qx, qy, qz)
  }
}