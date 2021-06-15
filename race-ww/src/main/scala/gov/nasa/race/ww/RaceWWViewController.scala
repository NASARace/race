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
import gov.nasa.race.common._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.swing.DeferredEDTAction
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww._

import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.animation.{AnimationController, DoubleAnimator, ScheduledInterpolator}
import gov.nasa.worldwind.geom.{Angle, LatLon, Position, Vec4}
import gov.nasa.worldwind.view.orbit.{BasicOrbitView, OrbitView, OrbitViewInputHandler, OrbitViewPropertyAccessor}
import gov.nasa.worldwind.awt.ViewInputAttributes

/**
  * a WorldWind ViewInputHandler that lets us query animation target positions and identify
  * user input related view changes
  */
class RaceWWViewController extends OrbitViewInputHandler {
  // we have to init this deferred because of WWJ initialization (setViewInputHandler() does
  // not properly unregister/register listeners)
  var raceViewer: RaceViewer = null
  var wwdView: RaceWWView = null
  var lastUserInputTime: Long = 0
  var lastInputEvent: InputEvent = _
  var pressedKey: Int = 0
  var mousePressed: Boolean = false
  var mouseDragged: Boolean = false

  val viewGoal = new ViewGoal // the goal of a view transition


  val viewChangeNotifier = DeferredEDTAction(300,900) {
    if (raceViewer != null) raceViewer.notifyViewChanged
  }

  def attachToRaceView(rv: RaceViewer) = {
    raceViewer = rv
    wwdView = rv.wwdView
  }

  // this is called by RaceViewer after the wwdView is realized and the window is visible
  def initialize: Unit = {
    viewGoal.setFromView(wwdView)
  }

  def targetViewPos = viewGoal.pos
  def targetViewZoom = viewGoal.zoom

  //--- non-animated

  def moveEyePosition (pos: Position): Unit = moveEyePosition(pos,viewGoal.zoom)

  def moveEyePosition (pos: Position, zoom: Double): Unit = {
    cancelAnimators
    viewGoal.pos = pos
    viewGoal.zoom = zoom
    viewGoal.setAnimationHint(Goto)
    wwdView.moveEyePosition(pos,zoom)
    raceViewer.notifyViewChanged // no point deferring
  }

  //--- animators

  def centerTo(pos: Position, transitionTime: Long): Unit = {
    addCenterAnimator(viewGoal.pos, pos, transitionTime, true)
  }
  def zoomTo (zoom: Double) = {
    addZoomAnimator(viewGoal.zoom, zoom)
  }
  def panTo (pos: Position, zoom: Double) = {
    addPanToAnimator(viewGoal.pos,pos,
                     viewGoal.heading,viewGoal.heading,
                     viewGoal.pitch,viewGoal.pitch,
                     viewGoal.zoom, zoom,
                     1500,false)
  }
  def pitchTo (angle: Angle) = {
    addPitchAnimator(viewGoal.pitch,angle)
  }
  def headingTo (angle: Angle) = {
    addHeadingAnimator(viewGoal.heading, angle)
  }
  def headingPitchTo (heading: Angle, pitch: Angle) = {
    val roll = viewGoal.roll
    addHeadingPitchRollAnimator(viewGoal.heading,heading,viewGoal.pitch,pitch,roll,roll)
  }
  def rollTo (angle: Angle) = {
    addRollAnimator(viewGoal.roll,angle)
  }

  override def stopAnimators: Unit = {
    super.stopAnimators
    viewGoal.setFromView(wwdView)
  }
  def cancelAnimators: Unit = {
    super.stopAnimators
  }

  //--- overridden animator management (make sure we always start from last viewGoal)

  override def addPanToAnimator (beginCenterPos: Position, endCenterPos: Position,
                                 beginHeading: Angle, endHeading: Angle,
                                 beginPitch: Angle, endPitch: Angle,
                                 beginZoom: Double, endZoom: Double,
                                 timeToMove: Long, endCenterOnSurface: Boolean): Unit = {
    val center0 = viewGoal.setPos(endCenterPos)
    val zoom0 = viewGoal.setZoom(endZoom)
    val heading0 = viewGoal.setHeading(endHeading)
    val pitch0 = viewGoal.setPitch(endPitch)
    viewGoal.setAnimationHint(Pan)

    super.addPanToAnimator(center0,endCenterPos,heading0,endHeading,pitch0,endPitch,zoom0,endZoom,timeToMove,endCenterOnSurface)
    viewChangeNotifier.run
  }

  override def addPanToAnimator (beginCenterPos: Position, endCenterPos: Position,
                                 beginHeading: Angle, endHeading: Angle,
                                 beginPitch: Angle, endPitch: Angle,
                                 beginZoom: Double, endZoom: Double,
                                 endCenterOnSurface: Boolean): Unit = {
    addPanToAnimator(beginCenterPos,endCenterPos,beginHeading,endHeading,beginPitch,endPitch,beginZoom,endZoom,
      1500, endCenterOnSurface)
  }

  override def addEyePositionAnimator (timeToIterate: Long, beginPosition: Position, endPosition: Position): Unit = {
    val pos0 = viewGoal.setPos(endPosition)
    viewGoal.setAnimationHint(EyePos)
    super.addEyePositionAnimator(timeToIterate,pos0,endPosition)
    viewChangeNotifier.run
  }

  override def addHeadingAnimator(begin: Angle, end: Angle): Unit = {
    val heading0 = viewGoal.setHeading(end)
    viewGoal.setAnimationHint(Heading)
    super.addHeadingAnimator(heading0,end)
    viewChangeNotifier.run
  }

  override def addPitchAnimator(begin: Angle, end: Angle): Unit = {
    val pitch0 = viewGoal.setPitch(end)
    viewGoal.setAnimationHint(Pitch)
    viewChangeNotifier.run

    super.addPitchAnimator(pitch0,end)
  }

  override def addRollAnimator(begin: Angle, end: Angle): Unit = {
    val roll0 = viewGoal.setRoll(end)
    viewGoal.setAnimationHint(Roll)
    viewChangeNotifier.run

    super.addRollAnimator(roll0,end)
  }

  override def addZoomAnimator (zoomStart: Double, zoomEnd: Double): Unit = {
    val zoom0 = viewGoal.setZoom(zoomEnd)
    viewGoal.setAnimationHint(Zoom)
    viewChangeNotifier.run

    //super.addZoomAnimator(zoom0,zoomEnd) // does not give us control over animation time
    val zoomAnimator = new DoubleAnimator(new ScheduledInterpolator(1000), zoomStart, zoomEnd,
                                          OrbitViewPropertyAccessor.createZoomAccessor(wwdView))
    gotoAnimControl.put(OrbitViewInputHandler.VIEW_ANIM_ZOOM, zoomAnimator)
    wwdView.firePropertyChange(AVKey.VIEW, null, wwdView)
  }

  override def addFlyToZoomAnimator(heading: Angle, pitch: Angle, zoom: Double): Unit = {
    viewGoal.heading = heading
    viewGoal.pitch = pitch
    viewGoal.zoom = zoom
    viewGoal.setAnimationHint(FlyTo)
    viewChangeNotifier.run

    super.addFlyToZoomAnimator(heading,pitch,zoom)
  }

  override def addCenterAnimator(begin: Position, end: Position, lengthMillis: Long, smoothed: Boolean): Unit = {
    val pos0 = viewGoal.setPos(end)
    viewGoal.setAnimationHint(Center)
    viewChangeNotifier.run

    super.addCenterAnimator(pos0,end,lengthMillis,smoothed)
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
    val sp = wwdView.computePositionFromScreenPoint(screenPoint.x, screenPoint.y)
    val pos = new Position(sp, 0)
    centerTo(pos, 1000)
  }

  def processMousePressed (e: MouseEvent): Boolean = {
    // we don't want the standard WW behavior of centering on single clicks
    if (e.getClickCount == 1) {
      if (isShift) {
        centerOnMouse
      }
      true
    } else false
  }

  def processMouseWheelMoved (e: MouseWheelEvent): Boolean = {
    val rot = e.getWheelRotation
    if (rot != 0) {
      val delta = if (rot < 0) 1 else if (rot > 0) -1 else 0

      if (isCtrl) {
        val a = viewGoal.pitch.degrees + delta
        ifWithin(a, 0, 90) {
          // TODO - this doesn't move the eye on a sphere around focused objects
          val newPitch = Angle.fromDegrees(a)
          viewGoal.setPitch(newPitch)
          wwdView.setPitch(newPitch)
          raceViewer.redrawNow
          viewChangeNotifier.schedule
        }
        true

      } else if (isAlt) {
        var a = viewGoal.heading.degrees + delta
        a = if (a > 360) a - 360 else if (a < 0) a + 360 else a
        val newHeading = Angle.fromDegrees(a)
        viewGoal.setHeading(newHeading)
        wwdView.setHeading(newHeading)
        raceViewer.redrawNow
        viewChangeNotifier.schedule
        true

      } else { // no modifier pressed
        false   // process normally
      }

    } else true // no rotation
  }

  //--- keep track of last user input time
  def recordInputEvent (e: InputEvent): Unit = {
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }

  override protected def handleMouseWheelMoved (e: MouseWheelEvent): Unit = {
    recordInputEvent(e)
    if (!processMouseWheelMoved(e)) super.handleMouseWheelMoved(e)
  }
  override protected def handleMouseDragged(e: MouseEvent): Unit = {
    recordInputEvent(e)
    mouseDragged = true
    super.handleMouseDragged(e)
  }
  override protected def handleMouseClicked(e: MouseEvent): Unit = {
    recordInputEvent(e)
    //super.handleMouseClicked(e)
  }
  override protected def handleMousePressed(e: MouseEvent): Unit = {
    recordInputEvent(e)
    mousePressed = true
    if (!processMousePressed(e)) super.handleMousePressed(e)
  }
  override protected def handleMouseReleased(e: MouseEvent): Unit = {
    recordInputEvent(e)
    mousePressed = false
    if (mouseDragged) {
      // WW does drag-to-rotate-globe
      viewGoal.pos = wwdView.getCenterPosition
      viewGoal.setAnimationHint(Center)
      viewChangeNotifier.run
      mouseDragged = false
    }
    //super.handleMouseReleased(e)
  }
  override protected def handleKeyPressed(e: KeyEvent): Unit = {
    recordInputEvent(e)
    if (!processKeyPressed(e)) super.handleKeyPressed(e)
  }
  override protected def handleKeyReleased(e: KeyEvent): Unit = {
    recordInputEvent(e)
    if (!processKeyReleased(e)) super.handleKeyReleased(e)
  }

  def setLastUserInput = lastUserInputTime = System.currentTimeMillis()
  def millisSinceLastUserInput: Long = System.currentTimeMillis - lastUserInputTime

  def lastUserInputWasClick = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_CLICKED
  def lastUserInputWasDrag = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_DRAGGED
  def lastUserInputWasWheel = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_WHEEL

  // the super method directly registers and starts a OrbitViewMoveToZoomAnimator
  override protected def changeZoom(view: BasicOrbitView, animControl: AnimationController, change: Double, attrib: ViewInputAttributes.ActionAttributes): Unit = {
    super.changeZoom(view,animControl,change,attrib)
    viewGoal.setAnimationHint(Zoom)
    // the animators end zoom is dynamically extended, hence we have to increase initial latency
    viewChangeNotifier.schedule(500)
  }

  /**
    * WW per default uses the eye altitude to compute the zoom level (same input at higher alt
    * causes larger zoom increment/decrement). We extend this with scaled linear (meta) and logarithmic zoom when
    * the meta or alt [+shift] key is pressed so that we can navigate in proximity of high objects
    */
  override protected def computeNewZoom(view: OrbitView, curZoom: Double, change: Double): Double = {
    var newZoom = if (isMeta) { // adapt zoom factor independent of current level
      curZoom + scale(Math.signum(change)*10)
    } else { // adapt zoom factor based on current level
      val logCurZoom = if (curZoom != 0) Math.log(curZoom) else 0
      Math.exp(logCurZoom + scale(change))
    }

    newZoom = view.getOrbitViewLimits.limitZoom(view, newZoom)
    viewGoal.zoom = newZoom

    newZoom
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

  def isAlt = (lastInputEvent != null) && ((lastInputEvent.getModifiersEx & ALT_DOWN_MASK) != 0)
  def isShift = (lastInputEvent != null) && ((lastInputEvent.getModifiersEx & SHIFT_DOWN_MASK) != 0)
  def isMeta = (lastInputEvent != null) && ((lastInputEvent.getModifiersEx & META_DOWN_MASK) != 0)
  def isCtrl = (lastInputEvent != null) && ((lastInputEvent.getModifiersEx & CTRL_DOWN_MASK) != 0)

}
