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

import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length.Meters


/**
  * RaceLayer that supports altitude aware rendering, e.g. to adapt the level of details shown
  */
trait AltitudeSensitiveRaceLayer extends RaceLayer with DeferredRenderingListener {

  def defaultIconThreshold: Length = Meters(1000000)
  val iconThresholdLevel = config.getDoubleOrElse("icon-altitude", defaultIconThreshold.toMeters)

  def defaultLabelThreshold: Length = Meters(1500000)
  val labelThresholdLevel = config.getDoubleOrElse("label-altitude", defaultLabelThreshold.toMeters)

  // above the labelThreshold we just have dots

  def defaultLineThreshold: Length = Meters(1500000)
  val lineThresholdLevel = config.getDoubleOrElse("line-altitude", defaultLineThreshold.toMeters)

  def defaultLinePosThreshold: Length = Meters(5000)
  val linePosThresholdLevel = config.getDoubleOrElse("linepos-altitude", defaultLinePosThreshold.toMeters)

  var eyeAltitude: Double = getEyeAltitude
  var oldEyeAltitude: Double = Double.MaxValue // we keep this to support detecting zoom-in/-out

  def checkNewEyeAltitude: Unit // to be provided by concrete type
  def initEyeAltitude: Unit = {}

  override def initializeLayer: Unit = {
    super.initializeLayer

    eyeAltitude = getEyeAltitude
    initEyeAltitude

    onBeforeBufferSwap(wwd, 400) { e =>
      eyeAltitude = getEyeAltitude

      if (eyeAltitude != oldEyeAltitude) {
        checkNewEyeAltitude
        oldEyeAltitude = eyeAltitude
      }
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
