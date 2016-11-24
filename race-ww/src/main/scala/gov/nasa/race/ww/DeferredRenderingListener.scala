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

import gov.nasa.worldwind.WorldWindow
import gov.nasa.worldwind.event.{RenderingEvent, RenderingListener}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * a batched RenderingListener that avoids excessive recomputation due to
 * RenderingEvent flooding by means of futures
 */
trait DeferredRenderingListener {

  class Listener (stage: String, delay: Int, action: (RenderingEvent)=>Any) extends RenderingListener {
    protected var pending: Option[Future[Any]] = None
    protected var lastEvent: RenderingEvent = _
    @volatile protected var lastTimeStamp: Long = 0

    override def stageChanged(event: RenderingEvent): Unit = {
      if (event.getStage == stage) {
        lastEvent = event
        lastTimeStamp = System.currentTimeMillis
        if (delay > 0) {
          synchronized {
            if (pending == None){
              pending = Some(Future{
                do {
                  Thread.sleep(delay)
                  //println(s"@@@ $lastEvent $lastTimeStamp")
                  action(lastEvent)
                } while ((System.currentTimeMillis - lastTimeStamp) < delay)
                synchronized{ pending = None }
              })
            }
          }
        } else  action(event)
      }
    }
  }

  def onBeforeBufferSwap (wwd: WorldWindow, delay: Int=300)(action: (RenderingEvent)=>Any) = {
    wwd.addRenderingListener( new Listener(RenderingEvent.BEFORE_BUFFER_SWAP, delay, action))
  }

  def onAfterBufferSwap (wwd: WorldWindow, delay: Int=300)(action: (RenderingEvent)=>Any) = {
    wwd.addRenderingListener( new Listener(RenderingEvent.AFTER_BUFFER_SWAP, delay, action))
  }

  def onBeforeRendering (wwd: WorldWindow, delay: Int=300)(action: (RenderingEvent)=>Any) = {
    wwd.addRenderingListener( new Listener(RenderingEvent.BEFORE_RENDERING, delay, action))
  }
}
