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

import gov.nasa.race.common._
import gov.nasa.worldwind.WorldWindow
import gov.nasa.worldwind.event.{SelectEvent, SelectListener}
import gov.nasa.worldwind.layers.Layer

import scala.collection.mutable.ListBuffer
import scala.swing.Component

/**
  * mixin for RACE specific information we want to add to WorldWind Layers
  *
  * Note that we want to be able to directly extend the respective WW layer class. e.g.
  * class FlightPosLayer (..) extends RenderableLayer with RaceLayerInfo {..}
  * hence this has to be a trait. The rationale is that we want to be able to directly add
  * instances of our own layers to the WW layerlist
  *
  * the reason why we keep this separate from RaceLayer is that we might also
  * have RaceLayerInfos that just decorate existing WorldWind (system) layers
  */

trait RaceLayerInfo {
  val name: String
  val categories: Set[String]
  val description: String

  val panel: LayerInfoPanel // to display layer specific information, might be shared between layer instances

  //--- those are for initialization
  val enable: Boolean
  val enablePick: Boolean

  def getLayer: Option[Layer] = if (this.isInstanceOf[Layer]) Some(this.asInstanceOf[Layer]) else None

  // this is suboptimal - those have to be initialized after the fact
  // we might have to turn them into options
  var wwd: WorldWindow = _
  var wwdRedrawManager: RedrawManager = _

  def redraw = if (wwdRedrawManager != null) wwdRedrawManager.redraw
  def redrawNow = if (wwdRedrawManager != null) wwdRedrawManager.redrawNow

  def initializeLayer (wwd: WorldWindow, wwdRedrawManager: RedrawManager): Unit = {
    this.wwd = wwd
    this.wwdRedrawManager = wwdRedrawManager
    initializeLayer()
  }
  protected def initializeLayer(): Unit = {}

  // to be called from overridden initializeLayer
  protected def onSelected (f: (SelectEvent) => Any) = {
    wwd.addSelectListener( new SelectListener(){
      override def selected (e: SelectEvent) = f(e)
    })
  }
}

/**
 * trait to support altitude aware rendering, e.g. to adapt the level of detail shown
 *
 * the gov.nasa.worldwind.layers.Layer setMin/MaxActiveAltitude is not enough if
 * the layer has to render altitude-aware, it is just all or nothing
 */
trait AltitudeSensitiveLayerInfo extends RaceLayerInfo with DeferredRenderingListener {
  var eyeAltitude = getEyeAltitude

  val thresholds = ListBuffer.empty[Threshold]  // TODO - this should be a sorted list

  override def initializeLayer (wwd: WorldWindow, wwdRedrawManager: RedrawManager): Unit = {
    thresholds.sortWith(_.threshold < _.threshold)
    eyeAltitude = getEyeAltitude
    super.initializeLayer(wwd,wwdRedrawManager)

    onBeforeBufferSwap(wwd, 400) { e =>
      val oldEyeAltitude = eyeAltitude
      val newEyeAltitude = getEyeAltitude
      eyeAltitude = newEyeAltitude

      // make sure we start to iterate from the thresholds limit towards we go
      // (we might cross several thresholds)
      val it = if (newEyeAltitude > oldEyeAltitude) thresholds.reverseIterator else thresholds.iterator
      while (it.hasNext && !it.next.checkCrossed(oldEyeAltitude, newEyeAltitude)){}
    }
  }

  def getEyeAltitude: Double = {
    // not very scalatic, but this is performance relevant
    if (wwd != null) {
      val view = wwd.getView
      if (view != null) {
        val eyePos = view.getEyePosition
        if (eyePos != null) return eyePos.getElevation
      }
    }
    Double.MaxValue // no luck
  }
}

trait DynamicRaceLayerInfo extends RaceLayerInfo {
  var count = 0// to keep track of changes

  def size: Int // answer number of items in layer
}
