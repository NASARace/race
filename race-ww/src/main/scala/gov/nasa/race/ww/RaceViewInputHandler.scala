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

import java.awt.event.{InputEvent, KeyEvent, MouseEvent, MouseWheelEvent}

import gov.nasa.worldwind.animation.AnimationController
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.view.orbit.OrbitViewInputHandler

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
    super.handleKeyPressed(e)
    lastInputEvent = e
    lastUserInputTime = System.currentTimeMillis
  }

  def setLastUserInput = lastUserInputTime = System.currentTimeMillis()
  def millisSinceLastUserInput: Long = System.currentTimeMillis - lastUserInputTime

  def lastUserInputWasClick = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_CLICKED
  def lastUserInputWasDrag = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_DRAGGED
  def lastUserInputWasWheel = lastInputEvent != null && lastInputEvent.getID == MouseEvent.MOUSE_WHEEL

  /***
  protected var _targetPosition: Position = null // we can't yet call getView.getEyePosition

  def targetPosition = {
    if (_targetPosition == null) getView.getEyePosition else _targetPosition
  }

  //--- eye position modifiers that can cause animations

  override def goTo (lookAtPos: Position, distance: Double): Unit = {
    _targetPosition = lookAtPos
    println(s"@@ goTo: $lookAtPos")
    super.goTo(lookAtPos, distance)
  }

  override def addZoomAnimator(zoomStart: Double, zoomEnd: Double): Unit = {
    println(s"@@ addZoomAnimator: $zoomStart $zoomEnd")
    super.addZoomAnimator(zoomStart,zoomEnd)
  }

  override def addFlyToZoomAnimator(heading: Angle, pitch: Angle, zoom: Double): Unit = {
    println(s"@@ addFlyToZoomAnimator")
    super.addFlyToZoomAnimator(heading,pitch,zoom)
  }


  override def changeZoom(view: BasicOrbitView, animControl: AnimationController,
                          change: Double, attrib: ViewInputAttributes.ActionAttributes): Unit = {
    super.changeZoom(view,animControl,change,attrib)

    val zoomAnimator = animControl.get(OrbitViewInputHandler.VIEW_ANIM_ZOOM).asInstanceOf[OrbitViewMoveToZoomAnimator]
    if (zoomAnimator != null) {
      val endZoom = zoomAnimator.getEnd
      println(s"@@ changeZoom: ${endZoom.toInt}")
    }
  }

  override def addCenterAnimator (begin: Position, end: Position, lengthMillis: Long, smoothed: Boolean): Unit = {
    _targetPosition = end
    println(s"@@ addCenterAnimator: $end")
    super.addCenterAnimator(begin, end, lengthMillis, smoothed)
  }

  override def addEyePositionAnimator(timeToIterate: Long, beginPosition: Position, endPosition: Position): Unit = {
    _targetPosition = endPosition
    println(s"@@ addEyePositionAnimator: $endPosition")
    super.addEyePositionAnimator(timeToIterate,beginPosition,endPosition)
  }

  override def addPanToAnimator(centerPos: Position, heading: Angle, pitch: Angle, zoom: Double): Unit = {
    _targetPosition = centerPos
    println(s"@@ addPanToAnimator1: $centerPos")
    super.addPanToAnimator(centerPos,heading,pitch,zoom)
  }

  override def addPanToAnimator(beginCenterPos: Position, endCenterPos: Position,
                                beginHeading: Angle, endHeading: Angle,
                                beginPitch: Angle, endPitch: Angle,
                                beginZoom: Double, endZoom: Double,
                                endCenterOnSurface: Boolean): Unit = {
    _targetPosition = endCenterPos
    println(s"@@ addPanToAnimator2: $endCenterPos")
    super.addPanToAnimator(beginCenterPos,endCenterPos,
      beginHeading,endHeading,beginPitch,endPitch,beginZoom,endZoom,endCenterOnSurface)
  }

  override def addPanToAnimator(beginCenterPos: Position, endCenterPos: Position,
                                beginHeading: Angle, endHeading: Angle,
                                beginPitch: Angle, endPitch: Angle,
                                beginZoom: Double, endZoom: Double,
                                timeToMove: Long, endCenterOnSurface: Boolean): Unit = {
    _targetPosition = endCenterPos
    println(s"@@ addPanToAnimator3: $endCenterPos")
    super.addPanToAnimator(beginCenterPos,endCenterPos,
      beginHeading,endHeading,beginPitch,endPitch,beginZoom,endZoom,timeToMove,endCenterOnSurface)
  }

  override def setCenterPosition(view: BasicOrbitView, animControl: AnimationController,
                                 position: Position, attrib: ViewInputAttributes.ActionAttributes): Unit = {
    _targetPosition = position
    println(s"@@ setCenterPosition: $position")
    super.setCenterPosition(view,animControl,position,attrib)
  }

  //--- stopping animators has to set the endPos to the current value

  override def stopGoToAnimators: Unit = {
    _targetPosition = getView.getEyePosition
    super.stopGoToAnimators
  }

  override def stopAllAnimators: Unit = {
    _targetPosition = getView.getEyePosition
    super.stopAllAnimators
  }

  override def stopUserInputAnimators(names: AnyRef*) = {
    _targetPosition = getView.getEyePosition
    super.stopUserInputAnimators(names:_*)
  }

    ***/
}
