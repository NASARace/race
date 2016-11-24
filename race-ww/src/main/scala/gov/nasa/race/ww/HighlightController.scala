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
import gov.nasa.worldwind.event._
import gov.nasa.worldwind.render.Highlightable

class HighlightController (val wwd: WorldWindow, val highlightEventType: Object = SelectEvent.ROLLOVER)
                                                                                     extends SelectListener {
  protected var lastHighlightObject: Option[Highlightable] = None

  wwd.addSelectListener(this)

  def dispose: Unit = wwd.removeSelectListener(this)

  def selected (event: SelectEvent): Unit = {
    try {
      if (event.getEventAction == highlightEventType) highlight(event.getTopObject)
    } catch {
      case e: Exception => sys.error(e.toString)  // <2do> logging without worldwindx ?
    }
  }

  protected def highlight (o: Object): Unit = {
    lastHighlightObject match {
      case Some(h) if (o != h) =>
        h.setHighlighted(false)
        lastHighlightObject = None
      case _ =>
    }
    o match {
      case h: Highlightable if (o != h) =>
        h.setHighlighted(true)
        lastHighlightObject = Some(h)
      case _ => // WorldWind apparently silently ignores it
    }
  }
}
