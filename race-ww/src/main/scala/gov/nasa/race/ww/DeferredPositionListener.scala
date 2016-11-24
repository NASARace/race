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

import java.awt.event.{ActionEvent, ActionListener}
import javax.swing.Timer

import gov.nasa.race.common._
import gov.nasa.worldwind.WorldWindow
import gov.nasa.worldwind.event.{PositionEvent, PositionListener}
import gov.nasa.worldwind.geom.Position
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * a (potentially) deferred PositionListener
 */
trait DeferredPositionListener {

  class Listener (delay: Int, updateTicks: Int, action: (PositionEvent)=>Any) extends PositionListener with ActionListener {
    val timer = new Timer(delay, this)
    timer.setRepeats(false)
    timer.setActionCommand("PositionUpdate")

    var count = updateTicks
    var isQueued: Boolean = false
    var lastPosition: Position = null
    var firstEvent, lastEvent: PositionEvent = null

    override def actionPerformed (e: ActionEvent): Unit = {
      if (lastEvent.ne(firstEvent)){
        count -= 1
        timer.restart
        if (count == 0){
          count = updateTicks
          action(new PositionEvent(lastEvent.getSource,
                                   lastEvent.getScreenPoint,
                                   firstEvent.getPreviousPosition,
                                   lastEvent.getPosition))
        }
      } else { // no position change
        timer.stop
        action(lastEvent)
      }
    }

    override def moved(event: PositionEvent): Unit = {
      if (!isQueued) {
        isQueued = true
        firstEvent = event
        lastEvent = event
        timer.start
      } else {
        lastEvent = event
      }
    }
  }

  def onMoved (wwd: WorldWindow, delay: Int = 200, updateTicks: Int = 3)(action: (PositionEvent) => Any) = {
    wwd.addPositionListener( new Listener(delay, updateTicks, action))
  }
}
