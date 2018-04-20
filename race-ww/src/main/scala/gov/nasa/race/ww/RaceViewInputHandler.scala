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

package gov.nasa.race.ww

import java.awt.Point
import java.awt.event.InputEvent._
import java.awt.event.{InputEvent, KeyEvent, MouseEvent, MouseWheelEvent}

import gov.nasa.worldwind.animation.AnimationController
import gov.nasa.worldwind.geom.{LatLon, Position, Vec4}
import gov.nasa.worldwind.view.orbit.{OrbitView, OrbitViewInputHandler}


/**
  * a WorldWind ViewInputHandler that lets us query animation target positions and identify
  * user input related view changes
  */
class RaceViewInputHandler extends OrbitViewInputHandler {
  // we have to init this deferred because of WWJ initialization (setViewInputHandler() does
  // not properly unregister/register listeners)
  var raceView: RaceView = null
  var lastUserInputTime: Long = 0
  var lastTargetPosition: Position = _
  var lastInputEvent: InputEvent = _
  var pressedKey: Int = 0

  def attachToRaceView(rv: RaceView) = raceView = rv

  override protected def setTargetEyePosition (eyePos: Position, animController: AnimationController, actionKey: String): Unit = {
    super.setTargetEyePosition(eyePos, animController, actionKey)
    val animationHint = actionKey match {
      case "ViewAnimZoom" => RaceView.Zoom
      case "ViewAnimCenter" =>  if (lastUserInputWasDrag) RaceView.CenterDrag else RaceView.CenterClick
      case "ViewAnimPan" => RaceView.Pan
      case other => RaceView.Goto
    }

    raceView.newTargetEyePosition(eyePos, animationHint)
    lastTargetPosition = eyePos // set this after notification so that we can still see the last pos
  }

  def processKeyPressed (e: KeyEvent): Boolean = {
    pressedKey = e.getKeyCode
    false
  }

  def processKeyReleased (e: KeyEvent): Boolean = {
    pressedKey = KeyEvent.VK_UNDEFINED
    e.getKeyCode match {
      case KeyEvent.VK_0 => raceView.pitchTo(ZeroWWAngle); true
      case _ => false
    }
  }

  //--- keep track of last user input time
  override protected def handleMouseWheelMoved (e: MouseWheelEvent): Unit = {
    super.handleMouseWheelMoved(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleMouseDragged(e: MouseEvent): Unit = {
    super.handleMouseDragged(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleMouseClicked(e: MouseEvent): Unit = {
    super.handleMouseClicked(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleMousePressed(e: MouseEvent): Unit = {
    super.handleMousePressed(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleMouseReleased(e: MouseEvent): Unit = {
    super.handleMouseReleased(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleKeyPressed(e: KeyEvent): Unit = {
    if (!processKeyPressed(e)) super.handleKeyPressed(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleKeyReleased(e: KeyEvent): Unit = {
    if (!processKeyReleased(e)) super.handleKeyReleased(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }

  def setLastUserInput = lastUserInputTime = System.currentTimeMillis()
  def millisSinceLastUserInput: Long = System.currentTimeMillis - lastUserInputTime

  def lastUserInputWasClick = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_CLICKED
  def lastUserInputWasDrag = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_DRAGGED
  def lastUserInputWasWheel = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_WHEEL

  /**
    * WW per default uses the eye altitude to compute the zoom level (same input at higher alt
    * causes more zoom). We extend this with scaled linear (meta) and logarithmic zoom when
    * the meta or alt [+shift] key is pressed so that we can navigate in proximity of high objects
    */
  override def computeNewZoom(view: OrbitView, curZoom: Double, change: Double): Double = {
    val newZoom = if (isMeta) { // adapt zoom factor independent of current level
      curZoom + scale(Math.signum(change)*10)
    } else { // adapt zoom factor based on current level
      val logCurZoom = if (curZoom != 0) Math.log(curZoom) else 0
      Math.exp(logCurZoom + scale(change))
    }

    view.getOrbitViewLimits.limitZoom(view, newZoom)
  }

  override def getChangeInLocation(point1: Point, point2: Point, vec1: Vec4, vec2: Vec4): LatLon = {
    // Modify the distance we'll actually travel based on the slope of world distance travelled to screen
    // distance travelled . A large slope means the user made a small change in screen space which resulted
    // in a large change in world space. We want to reduce the impact of that change to something reasonable.
    val dragSlope = computeDragSlope(point1, point2, vec1, vec2)
    val dragSlopeFactor = getDragSlopeFactor

    var scale = 1.0 / (1.0 + dragSlopeFactor * dragSlope * dragSlope)
    val globe = wwd.getModel.getGlobe
    val pos1 = globe.computePositionFromPoint(vec1)
    val pos2 = globe.computePositionFromPoint(vec2)

    val alt = wwd.getView.getEyePosition.elevation
    if (isAlt)  scale /= (alt/1000)

    val adjustedLocation = LatLon.interpolateGreatCircle(scale, pos1, pos2)
    // Return the distance to travel in angular degrees.
    pos1.subtract(adjustedLocation)
  }

  def scale (d: Double): Double = {
    if (isAlt) {
      if (isShift) d * 10 else d / 10
    } else d
  }

  def isAlt = (lastInputEvent != null) && ((lastInputEvent.getModifiers & ALT_MASK) != 0)
  def isShift = (lastInputEvent != null) && ((lastInputEvent.getModifiers & SHIFT_MASK) != 0)
  def isMeta = (lastInputEvent != null) && ((lastInputEvent.getModifiers & META_MASK) != 0)
  def isCtrl = (lastInputEvent != null) && ((lastInputEvent.getModifiers & CTRL_MASK) != 0)
}
