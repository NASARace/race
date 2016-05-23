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

package gov.nasa.race.ww.layers

import java.awt.Color

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.data.IdentifiableInFlightAircraft
import gov.nasa.race.ww._
import gov.nasa.race.ww.AircraftDetailLevel._
import gov.nasa.race.swing.Style._
import scala.collection.mutable.{Map => MutableMap}


/**
  * abstract layer class to display aircraft in flight
  */
abstract class InFlightAircraftLayer[T <:IdentifiableInFlightAircraft] (raceView: RaceView,config: Config)
                                  extends SubscribingRaceLayer(raceView,config)
                                     with DynamicRaceLayerInfo
                                     with AltitudeSensitiveRaceLayerInfo
                                     with AircraftRenderContext[T] {

  val panel = new DefaultDynamicLayerInfoPanel().styled('consolePanel)

  var labelThreshold = config.getDoubleOrElse("label-altitude", 1400000.0)
  var symbolThreshold = config.getDoubleOrElse("symbol-altitude", 1000000.0)
  val thresholds = Array(symbolThreshold,labelThreshold)
  override def crossedEyeAltitudeThreshold (oldAlt: Double, newAlt: Double, threshold: Double) = updateAllAttributes

  def defaultSymbolColor = Color.yellow
  val color = config.getColorOrElse("color", defaultSymbolColor)
  val planeImg = Images.getPlaneImage(color)
  val labelColor = toABGRString(color)
  val lineColor = labelColor

  var aircraft = MutableMap[String, AircraftPlacemark[T]]()

  override  def size = aircraft.size
  override def image (t: T) = planeImg
  override def detailLevel = {
    val alt = eyeAltitude
    if (alt > labelThreshold) DotLevel
    else if (alt > symbolThreshold) LabelLevel
    else SymbolLevel
  }

  def updateAllAttributes = {
    aircraft.foreach { e => e._2.updateAttributes }
    wwdRedrawManager.redrawNow()
  }
}
