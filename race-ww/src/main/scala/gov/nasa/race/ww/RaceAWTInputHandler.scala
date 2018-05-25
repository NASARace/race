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

import java.awt.event.MouseEvent

import gov.nasa.worldwind.awt.AWTInputHandler

/**
  * we provide our own WW InputHandler since the stock AWTInputHandler does redraws on
  * all mouse move/exit events. Given that we have layers with thousands of Renderables and
  * there is no need to highlight on mouse-overs, we have to cut down on redraws in order to not
  * steal CPU cycles from other actors
  */
class RaceAWTInputHandler extends AWTInputHandler {

  override def mouseExited (e: MouseEvent): Unit = {
    callMouseExitedListeners(e)

    wwd.getView.getViewInputHandler.mouseExited(e)
    if (wwd.getSceneController != null) wwd.getSceneController.setPickPoint(null)
    cancelHover
    cancelDrag
  }

  override def mouseMoved (e: MouseEvent): Unit = {
    mousePoint = e.getPoint
    callMouseMovedListeners(e)

    if (!e.isConsumed) wwd.getView.getViewInputHandler.mouseMoved(e)

    val sc = wwd.getSceneController
    if (sc != null) {
      sc.setPickPoint(mousePoint)
    }
  }
}
