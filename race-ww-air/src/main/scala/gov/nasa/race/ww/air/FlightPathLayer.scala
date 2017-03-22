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

package gov.nasa.race.ww.air

import java.awt.Color
import java.util.Vector

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.{FlightCompleted, FlightDropped, FlightPos}
import gov.nasa.race.common.Threshold
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww.{DynamicLayerInfoPanel, DynamicRaceLayerInfo, _}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.render.{BasicShapeAttributes, Material, Path}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{Map => MutableMap}

object FlightPathLayer {
  val flightPathLayerPanel = new DynamicLayerInfoPanel styled 'consolePanel
}
import gov.nasa.race.ww.air.FlightPathLayer._

/**
  * layer to display flight paths
  * Although this should be conceptually integrated into FlightPosLayer to save
  * the map, we keep it separate because we use a different Renderable.
  * For now this only records WW Positions, but we could easily extend to save
  * FlightPos objects so that we can select/decorate path nodes
  */
class FlightPathLayer (raceView: RaceView,config: Config)
                                   extends SubscribingRaceLayer(raceView,config)
                                      with DynamicRaceLayerInfo with AltitudeSensitiveLayerInfo {
  /**
    * NOTE - WorldWind's Path is not thread safe, and it is not enough to have a thread safe
    * Iterable positions object. The Iterable needs to have a thread safe Iterator
    * or we have to make sure addFlightPosition() is executed in the event dispatcher
    */
  class FPosPath (fpos: FlightPos, showPos: Boolean) extends Path {
    val attrs = new BasicShapeAttributes
    attrs.setOutlineWidth(1)
    attrs.setOutlineMaterial(new Material(color))
    attrs.setEnableAntialiasing(true)
    setAttributes(attrs)

    setShowPositions(showPos)
    setShowPositionsScale(6.0)
    // we don't use setShowPositionsThreshold because it is based on eye distance to position, not altitude
    setPathType(AVKey.LINEAR)
    setAltitudeMode(WorldWind.ABSOLUTE)

    var posList = new Vector[Position]
    addFlightPosition(fpos)

    def addFlightPosition (fpos: FlightPos) = {
      posList.add(fpos)
      setPositions(posList)
    }

    override def computePositionCount = numPositions = posList.size
  }

  val color = config.getColorOrElse("color", Color.yellow)
  val panel = flightPathLayerPanel

  val maxPathSize = config.getIntOrElse("max-size", 5)
  var flightPaths = TrieMap[String, FPosPath]()
  def size = flightPaths.size

  if (getMaxActiveAltitude == Double.MaxValue) setMaxActiveAltitude(600000)
  val showVertices = config.getBooleanOrElse("show-vertices", true)
  val vertexThreshold = config.getDoubleOrElse("vertex-altitude", 40000.0)

  thresholds += new Threshold(vertexThreshold, updateAllAttributes)

  @inline def displayVertices = showVertices && (eyeAltitude < vertexThreshold)

  override def handleMessage = {
    case BusEvent(_, fpos: FlightPos, _) =>
      count = count + 1
      val key = fpos.cs
      flightPaths.get(key) match {
        case Some(path: FPosPath) =>
          path.addFlightPosition(fpos)
        case None =>
          val path = new FPosPath(fpos, displayVertices)
          flightPaths += (key -> path)
          addRenderable(path)
      }
      wwdRedrawManager.redraw()

    case BusEvent(_,msg: FlightCompleted,_) =>
      count = count + 1
      removePath(msg.cs)

    case BusEvent(_,msg: FlightDropped,_) =>
      count = count + 1
      removePath(msg.cs)

    case other => warning(f"$name ignoring message $other%30.30s..")
  }

  def removePath (key: String) = {
    ifSome(flightPaths.get(key)){ path =>
      removeRenderable(path)
      flightPaths -= key
      wwdRedrawManager.redraw()
    }
  }

  def updateAllAttributes = {
    val dv = displayVertices
    flightPaths.foreach( e => e._2.setShowPositions(dv))
    wwdRedrawManager.redrawNow()
  }
}
