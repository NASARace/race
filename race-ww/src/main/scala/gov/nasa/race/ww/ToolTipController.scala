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

import gov.nasa.worldwind._
import gov.nasa.worldwind.avlist._
import gov.nasa.worldwind.event._
import gov.nasa.worldwind.layers._
import gov.nasa.worldwind.util._

import gov.nasa.race._

/**
  * a singleton WWJ SelectListener that is responsible for rollover, hover and click processing that is independent
  * of RaceLayers
  *
  * TODO - check if we really need selection listeners outside of Layers since we only have the raw Renderable types here
  */
class ToolTipController (val wwd: WorldWindow, val layer: AnnotationLayer,
                         val rolloverKey: String=AVKey.DISPLAY_NAME, val hoverKey: String=null)
                                                        extends SelectListener with Disposable {
  protected var lastRolloverObject: Option[Object] = None
  protected var lastHoverObject: Option[Object] = None
  protected var annotation: ToolTipAnnotation = _

  wwd.addSelectListener(this)

  def dispose: Unit = wwd.removeSelectListener(this)

  def getHoverText(event: SelectEvent): String = {
    event.getTopObject match {
      case l: AVList => l.getStringValue(hoverKey)
      case _ => null
    }
  }

  def getRolloverText(event: SelectEvent): String = {
    event.getTopObject match {
      case l: AVList => l.getStringValue(rolloverKey)
      case _ => null
    }
  }

  def selected (event: SelectEvent): Unit = {
    try {
      if (event.isRollover && rolloverKey != null)
        handleRollover(event)
      else if (event.isHover && hoverKey != null)
        handleHover(event)
    } catch {
      case x: Exception => sys.error(x.toString)
    }
  }

  protected def handleRollover (event: SelectEvent) = {
    ifSome(lastRolloverObject){ (o) =>
      if (o != event.getTopObject || WWUtil.isEmpty(getRolloverText(event))){
        hideToolTip()
        lastRolloverObject = None
        wwd.redraw()
      }
    }
    ifNotNull(getRolloverText(event)) { (txt) =>
      val obj = Some(event.getTopObject)

      lastRolloverObject = obj
      showToolTip(event, txt.replace("\\n", "\n"))
      wwd.redraw()
    }
  }

  protected def handleHover (event: SelectEvent) = {
    ifSome(lastHoverObject){ (o) =>
      if (o != event.getTopObject){
        hideToolTip()
        lastHoverObject = None
        wwd.redraw()
      }
      ifNotNull(getHoverText(event)){ (txt) =>
        lastHoverObject = Some(event.getTopObject)
        showToolTip( event, txt.replace("\\n", "\n"))
        wwd.redraw()
      }
    }
  }

  protected def showToolTip (event: SelectEvent, text: String) = {
    if (annotation != null){
      annotation.setText(text)
      annotation.setScreenPoint(event.getPickPoint)
    } else
      annotation = new ToolTipAnnotation(text)

    layer.removeAllAnnotations()
    layer.addAnnotation(annotation)
  }

  protected def hideToolTip() = {
    layer.removeAllAnnotations()

    if (annotation != null){
      annotation.dispose
      annotation = null
    }
  }
}
