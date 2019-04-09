/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import java.awt.Color

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.layers.RenderableLayer
import gov.nasa.worldwind.render.{BasicShapeAttributes, Material, Renderable, SurfaceCircle}

trait ConfigurableArea extends Renderable {
  val config: Config
  val name = config.getStringOrElse("name","")
}

class CircularArea (val config: Config) extends SurfaceCircle with ConfigurableArea {

  val centerPos = Position.fromDegrees(config.getDouble("lat"), config.getDouble("lon"))
  val radius = config.getLength("radius")
  val color = config.getColorOrElse("color", new Color(0,0,0))

  val attrs = new BasicShapeAttributes
  attrs.setDrawInterior(true)
  attrs.setDrawOutline(false)
  attrs.setInteriorMaterial(new Material(color))
  attrs.setInteriorOpacity(color.getAlpha / 255.0)

  setAttributes(attrs)

  setCenter(centerPos)
  setRadius(radius.toMeters)
}


/**
  * a layer with optionally configured areas that are displayed in the same color with a
  * variable alpha. Useful to create background contrast
  */
class AreaLayer (val raceViewer: RaceViewer, val config: Config) extends RenderableLayer with RaceLayer {

  var areas: Seq[ConfigurableArea] = createAreas
  val panel = new StaticLayerInfoPanel(this, size)

  areas.foreach(addRenderable)

  def size: Int = areas.size

  def createAreas: Seq[ConfigurableArea] = config.getConfigSeq("areas").flatMap(createArea)
  def createArea (areaConf: Config): Option[ConfigurableArea] = raceViewer.configurable[ConfigurableArea](areaConf)


}
