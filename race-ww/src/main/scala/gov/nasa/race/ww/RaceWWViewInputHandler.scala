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

import gov.nasa.race._
import Implicits._
import gov.nasa.race.geo.LatLonPos
import gov.nasa.worldwind.geom.{Angle, LatLon, Position, Vec4}
import gov.nasa.worldwind.view.orbit.{OrbitView, OrbitViewInputHandler}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._

/**
  * helper to keep track of view states, e.g. to store animation targets
  */
class ViewState {
  var lat: Double     = 0   // in [deg]
  var lon: Double     = 0
  var zoom: Double    = 0  // in [m]
  var heading: Double = 0 // in [deg]
  var pitch: Double   = 0
  var roll: Double    = 0

  def toPosition: Position = Position.fromDegrees(lat,lon,zoom)
  def toLatLonPos: LatLonPos = LatLonPos(Degrees(lat),Degrees(lon))
  def altitude: Length = Meters(zoom)


  def set (pos: Position): Unit = {
    lat = pos.latitude.degrees
    lon = pos.longitude.degrees
  }

  def set (pos: Position, _zoom: Double): Unit = {
    set(pos)
    zoom = _zoom
  }

  def set (pos: Position, _zoom: Double, _heading: Angle, _pitch: Angle): Unit = {
    set(pos,_zoom)
    heading = _heading.degrees
    pitch = _pitch.degrees
  }

  def set (pos: Position, _zoom: Double, _heading: Angle, _pitch: Angle, _roll: Angle): Unit = {
    set(pos,_zoom,_heading,_pitch)
    roll = _roll.degrees
  }
}

/**
  * a WorldWind ViewInputHandler that lets us query animation target positions and identify
  * user input related view changes
  */
class RaceWWViewInputHandler extends OrbitViewInputHandler {
  // we have to init this deferred because of WWJ initialization (setViewInputHandler() does
  // not properly unregister/register listeners)
  var raceViewer: RaceViewer = null
  var wwdView: RaceWWView = null
  var lastUserInputTime: Long = 0
  var lastInputEvent: InputEvent = _
  var pressedKey: Int = 0

  var lastTargetPosition: Position = _
  val targetView = new ViewState // end state of last scheduled animation

  def attachToRaceView(rv: RaceViewer) = {
    raceViewer = rv
    wwdView = rv.wwdView
  }

  // PHASE OUT
  /**
  override protected def setTargetEyePosition (eyePos: Position, animController: AnimationController, actionKey: String): Unit = {
    super.setTargetEyePosition(eyePos, animController, actionKey)
    val animationHint = actionKey match {
      case "ViewAnimZoom" => RaceViewer.Zoom
      case "ViewAnimCenter" =>  if (lastUserInputWasDrag) RaceViewer.CenterDrag else RaceViewer.CenterClick
      case "ViewAnimPan" => RaceViewer.Pan
      case other => RaceViewer.Goto
    }

    // TODO - we should get these either from arguments or the animations
    val pitch = wwdView.getPitch
    val heading = wwdView.getHeading
    val roll = wwdView.getRoll

    raceViewer.viewChanged(eyePos, heading,pitch,roll, animationHint) // notify with the intended position
    lastTargetPosition = eyePos // but set this after notification so that listeners can still see the last pos and compute deltas
  }
    **/

  //--- overridden animator scheduling to keep track of the last target view state (we should get this from the animationController)

  override def addPanToAnimator (beginCenterPos: Position, endCenterPos: Position,
                                 beginHeading: Angle, endHeading: Angle,
                                 beginPitch: Angle, endPitch: Angle,
                                 beginZoom: Double, endZoom:
                                 Double, timeToMove: Long, endCenterOnSurface: Boolean): Unit = {
    targetView.set(endCenterPos,endZoom,endHeading,endPitch)
    super.addPanToAnimator(beginCenterPos,endCenterPos,beginHeading,endHeading,beginPitch,endPitch,beginZoom,endZoom,timeToMove,endCenterOnSurface)
  }

  override def addPanToAnimator (beginCenterPos: Position, endCenterPos: Position,
                                 beginHeading: Angle, endHeading: Angle,
                                 beginPitch: Angle, endPitch: Angle,
                                 beginZoom: Double, endZoom: Double,
                                 endCenterOnSurface: Boolean): Unit = {
    targetView.set(endCenterPos,endZoom,endHeading,endPitch)
    super.addPanToAnimator(beginCenterPos,endCenterPos,beginHeading,endHeading,beginPitch,endPitch,beginZoom,endZoom,endCenterOnSurface)
  }

  override def addEyePositionAnimator (timeToIterate: Long, beginPosition: Position, endPosition: Position): Unit = {
    targetView.set(endPosition)
    super.addEyePositionAnimator(timeToIterate,beginPosition,endPosition)
  }

  override def addHeadingAnimator(begin: Angle, end: Angle): Unit = {
    targetView.heading = end.degrees
    super.addHeadingAnimator(begin,end)
  }

  override def addPitchAnimator(begin: Angle, end: Angle): Unit = {
    targetView.pitch = end.degrees
    super.addPitchAnimator(begin,end)
  }

  override def addRollAnimator(begin: Angle, end: Angle): Unit = {
    targetView.roll = end.degrees
    super.addRollAnimator(begin,end)
  }

  override def addZoomAnimator (zoomStart: Double, zoomEnd: Double): Unit = {
    targetView.zoom = zoomEnd
    super.addZoomAnimator(zoomStart,zoomEnd)
  }

  override def addFlyToZoomAnimator(heading: Angle, pitch: Angle, zoom: Double): Unit = {
    targetView.heading = heading.degrees
    targetView.pitch = pitch.degrees
    targetView.zoom = zoom
    super.addFlyToZoomAnimator(heading,pitch,zoom)
  }

  override def addCenterAnimator(begin: Position, end: Position, lengthMillis: Long, smoothed: Boolean): Unit = {
    targetView.set(end)
    super.addCenterAnimator(begin,end,lengthMillis,smoothed)
  }


  def hasActiveAnimation: Boolean = gotoAnimControl.hasActiveAnimation

  // we try to keep hotkeys global to avoid implicit modes but at some point we might have
  // layers that are key listeners

  def processKeyPressed (e: KeyEvent): Boolean = {
    pressedKey = e.getKeyCode
    false
  }

  def processKeyReleased (e: KeyEvent): Boolean = {
    pressedKey = KeyEvent.VK_UNDEFINED
    e.getKeyCode match {
      case KeyEvent.VK_0 =>
        raceViewer.resetView
        true
      case KeyEvent.VK_C =>
        centerOnMouse
        false
      case KeyEvent.VK_Z =>
        ifSome(raceViewer.focusObject) { o =>
          raceViewer.zoomInOn(o.pos)
        }
        false
      case _ => false
    }
  }

  def centerOnMouse: Unit = {
    val screenPoint = getMousePoint
    val pos = raceViewer.wwdView.computePositionFromScreenPoint(screenPoint.x, screenPoint.y)
    raceViewer.panToCenter(pos)
  }

  def processMouseWheelMoved (e: MouseWheelEvent): Boolean = {
    if (isCtrl) {
      val dPitch = if (e.getWheelRotation > 0) 2 else -2
      val pitch = wwdView.getPitch.addDegrees(dPitch)
      //raceView.pitchTo(pitch)
      wwdView.setPitch(pitch)
      //val cp = raceView.focusObject.get.pos
      //wwdView.asInstanceOf[OrbitView].setCenterPosition(cp)
      raceViewer.redrawNow
      true
    } else if (isAlt) {
      val dHdg = if (e.getWheelRotation > 0) 2 else -2
      val hdg = wwdView.getHeading.addDegrees(dHdg)
      wwdView.setHeading(hdg)
      raceViewer.redrawNow
      true
    } else false
  }

  //--- keep track of last user input time
  override protected def handleMouseWheelMoved (e: MouseWheelEvent): Unit = {
    if (!processMouseWheelMoved(e)) {
      //ifSome(raceView.focusPoint) {wwdView.asInstanceOf[OrbitView].setCenterPosition(_)}
      super.handleMouseWheelMoved(e)
    }

    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleMouseDragged(e: MouseEvent): Unit = {
    super.handleMouseDragged(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }
  override protected def handleMouseClicked(e: MouseEvent): Unit = {
    // we don't want the WW pan-to-center behavior in case we didn't hit a pick object (this is confusing implicit mode)
    //super.handleMouseClicked(e)
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
    * causes larger zoom increment/decrement). We extend this with scaled linear (meta) and logarithmic zoom when
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
