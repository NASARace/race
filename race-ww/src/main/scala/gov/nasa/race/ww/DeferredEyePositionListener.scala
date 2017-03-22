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

import java.awt.event.{ActionListener, ActionEvent}
import javax.swing.Timer
import gov.nasa.worldwind.WorldWindow
import gov.nasa.worldwind.event.{RenderingListener, RenderingEvent}
import gov.nasa.worldwind.geom.Position

/**
  * a WW listener for view eye position changes
  */
trait DeferredEyePositionListener {

  class Listener (wwd: WorldWindow, stage: String, delay: Int, updateTicks: Int, action: (Position)=>Any)
                                            extends RenderingListener with ActionListener{
    val timer = new Timer(delay, this)
    timer.setRepeats(false)
    timer.setActionCommand("EyePositionUpdate")
    var count = updateTicks

    var isQueued: Boolean = false
    var lastEyePos: Position = wwd.getView.getEyePosition

    override def actionPerformed (e: ActionEvent): Unit = {
      val eyePos = wwd.getView.getEyePosition
      if (eyePos != lastEyePos){ // still transient, reschedule
        lastEyePos = eyePos
        timer.restart
        count -= 1
        if (count == 0) {
          action(eyePos)
          count =  updateTicks
        }

      } else {
        isQueued = false
        count =  updateTicks
        timer.stop
        action(eyePos)
      }
    }

    override def stageChanged(event: RenderingEvent): Unit = {
      if (event.getStage == stage) {
        if (!isQueued) {
          // otherwise there is no need to inject a new message
          val view = wwd.getView
          if (view != null) {
            val eyePos = view.getEyePosition
            if (eyePos != lastEyePos) {
              isQueued = true
              lastEyePos = eyePos
              timer.start
            }
          }
        }
      }
    }
  }

  def onEyePositionChange (wwd: WorldWindow, delay: Int=500, updateTicks: Int=2)(action: (Position)=>Any) = {
    wwd.addRenderingListener( new Listener(wwd, RenderingEvent.AFTER_BUFFER_SWAP, delay, updateTicks, action))
  }
}
