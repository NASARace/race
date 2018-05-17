/*
 * Copyright (c) 2018, United States Government, as represented by the
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

package gov.nasa.race.ww

import gov.nasa.race._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.Meters
import gov.nasa.worldwind.geom.{Angle, Position}

/**
  * data object specifying the goal of a WW view transition
  */
class ViewGoal {
  var pos: Position   = Position.ZERO
  var zoom: Double    = 0  // in [m]
  var heading: Angle  = Angle.ZERO
  var pitch: Angle    = Angle.ZERO
  var roll: Angle     = Angle.ZERO

  var animationHint: String = NoAnimation

  def altitude: Length = Meters(zoom)

  def setFromView(wwdView: RaceWWView): Unit = {
    pos = wwdView.getCenterPosition
    zoom = wwdView.getZoom
    heading = wwdView.getHeading
    pitch = wwdView.getPitch
    roll = wwdView.getRoll
  }

  override def toString: String = s"View(pos:$pos,zoom:$zoom,hdg:$heading,pitch:$pitch,hint:$animationHint)"

  //--- convenience setters

  def setPos (v: Position) = updateField(v,pos,pos_=)
  def setZoom (v: Double) = updateField(v,zoom,zoom_=)
  def setHeading (v: Angle) = updateField(v,heading,heading_=)
  def setPitch (v: Angle) = updateField(v,pitch,pitch_=)
  def setRoll (v: Angle) = updateField(v,roll,roll_=)

  def setAnimationHint (hint: String): Unit = {
    animationHint = hint // TODO - we should probably accumulate here
  }
}
