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

import java.lang.{Iterable => JavaIterable}

import com.typesafe.config.Config
import gov.nasa.race.core._
import gov.nasa.race.data.{ITWSGridProjection, PrecipImage, _}
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww._
import gov.nasa.worldwind.geom.LatLon
import gov.nasa.worldwind.render.SurfaceImage

import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MutableMap}

object WeatherLayer {
  class PrecipEntry (val pi: PrecipImage) extends SurfaceImage(pi.img, asJavaIterable(computeGridCorners(pi))) {
    def update (newPi: PrecipImage) = setImageSource(newPi.img, corners)
  }

  def computeGridCorners(pi: PrecipImage): Array[LatLon] = {
    val proj = new ITWSGridProjection(pi.trpPos, pi.xoffset, pi.yoffset, pi.rotation)
    // order is sw,se,ne,nw
    Array[LatLon]( latLonPos2LatLon( proj.toLatLonPos(Length0, Length0)),
                   latLonPos2LatLon( proj.toLatLonPos(pi.width, Length0)),
                   latLonPos2LatLon( proj.toLatLonPos(pi.width, pi.height)),
                   latLonPos2LatLon( proj.toLatLonPos(Length0, pi.height)))
  }

  val weatherLayerPanel = new DynamicLayerInfoPanel styled 'consolePanel
}
import WeatherLayer._

/**
 * a WorldWind layer to display weather data
 */
class WeatherLayer (raceView: RaceView, config: Config)
                          extends SubscribingRaceLayer(raceView,config) with DynamicRaceLayerInfo {

  val precipMap = MutableMap[String,PrecipEntry]()
  val panel = weatherLayerPanel

  override def size = precipMap.size

  override def handleMessage = {
    case BusEvent(_,pi:PrecipImage,_) =>
      count = count+1

      // only add/keep images that have precipitation
      precipMap.get(pi.id) match {
        case Some(pe: PrecipEntry) =>
          if (pi.maxPrecipLevel > 0) {
            pe.update(pi)
          } else {
            removeRenderable(pe)
            precipMap -= pi.id
          }

        case None =>
          if (pi.maxPrecipLevel > 0) {
            val pe = new PrecipEntry(pi)
            addRenderable(pe)
            precipMap += (pi.id -> pe)
          }
      }

      wwdRedrawManager.redraw()

    case other => warning(f"$name ignoring message $other%30.30s..")
  }
}
