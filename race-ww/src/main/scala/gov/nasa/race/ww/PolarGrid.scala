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

import java.awt.{Color, Insets, Point}
import java.util

import gov.nasa.race._
import gov.nasa.race.geo.{GreatCircle, GeoPosition}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.{Angle, Length}
import gov.nasa.race.ww.Implicits._
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.geom.LatLon
import gov.nasa.worldwind.layers.RenderableLayer
import gov.nasa.worldwind.render._


/**
  * a configurable polar grid that can be dynamically positioned and rotated
  *
  * TODO - this should probably be its own Renderable, to be able to rotate the angular grid lines
  * and avoid the annotation overhead
  */
class PolarGrid (var center: GeoPosition, val heading: Angle, val ringInc: Length, val nRings: Int,
                 layer: RenderableLayer, gridColor: Color, gridAlpha: Float, fillColor: Color, fillAlpha: Float) {
  val gridMaterial = new Material(gridColor)
  val gridAttrs: BasicShapeAttributes = createGridAttrs
  val labelAttrs: AnnotationAttributes = createLabelAttrs
  val radius = ringInc * nRings
  var isShowing = false

  val outerRing = createOuterRing(radius.toMeters)
  val innerRings = createInnerRings

  val gls = createGridLines
  val polys = createPolys
  val labels = createLabels

  def createGridAttrs = {
    yieldInitialized(new BasicShapeAttributes) { attrs =>
      attrs.setDrawInterior(false)
      attrs.setDrawOutline(true)
      attrs.setOutlineMaterial(gridMaterial)
      attrs.setOutlineOpacity(gridAlpha)
      attrs.setOutlineWidth(1)
    }
  }

  def createLabelAttrs = {
    yieldInitialized(new AnnotationAttributes) { attrs =>
      attrs.setFrameShape(AVKey.SHAPE_NONE)
      attrs.setTextColor(gridColor)
      attrs.setOpacity(gridAlpha)
      attrs.setDrawOffset(new Point(0, 0))
      attrs.setLeaderGapWidth(0)
      attrs.setInsets(new Insets(0,0,0,0))
    }
  }

  def createOuterRing (r: Double): SurfaceCircle = {
    val attrs = new BasicShapeAttributes
    attrs.setDrawInterior(true)
    attrs.setDrawOutline(true)
    attrs.setInteriorMaterial(new Material(fillColor))
    attrs.setInteriorOpacity(fillAlpha)
    attrs.setOutlineMaterial(gridMaterial)
    attrs.setOutlineOpacity(gridAlpha)
    attrs.setOutlineWidth(1)

    yieldInitialized(new SurfaceCircle(latLonPos2LatLon(center),r))( _.setAttributes(attrs))
  }

  def createInnerRings = {
    val rings = new Array[SurfaceCircle](nRings -1)
    for (i <- 0 until nRings-1) rings(i) = createInnerRing( ringInc.toMeters * (i+1))
    rings
  }

  def createInnerRing (r: Double): SurfaceCircle = yieldInitialized(new SurfaceCircle(latLonPos2LatLon(center), r)){
    _.setAttributes(gridAttrs)
  }

  def gridLatLon(r: Length, phi: Angle) = latLonPos2LatLon(GreatCircle.endPos(center, r, phi))
  def gridPos(r: Length, phi: Angle) = wwPosition(GreatCircle.endPos(center, r, phi))

  def createGridLines: Array[util.ArrayList[LatLon]] = {
    val line0 = new util.ArrayList[LatLon](2)
    line0.add(gridLatLon(radius, Degrees(270)))
    line0.add(gridLatLon(radius, Degrees(90)))

    val line1 = new util.ArrayList[LatLon](2)
    line1.add(gridLatLon(radius, Degrees(0)))
    line1.add(gridLatLon(radius, Degrees(180)))

    val line2 = new util.ArrayList[LatLon](2)
    line2.add(gridLatLon(radius, Degrees(315)))
    line2.add(gridLatLon(radius, Degrees(135)))

    val line3 = new util.ArrayList[LatLon](2)
    line3.add(gridLatLon(radius, Degrees(45)))
    line3.add(gridLatLon(radius, Degrees(225)))

    Array( line0, line1, line2, line3 )
  }

  def setGridLines = {
    val line0 = gls(0)
    line0.set(0,gridLatLon(radius, Degrees(270)))
    line0.set(1,gridLatLon(radius, Degrees(90)))
    polys(0).setLocations(line0)

    val line1 = gls(1)
    line1.set(0,gridLatLon(radius, Degrees(0)))
    line1.set(1,gridLatLon(radius, Degrees(180)))
    polys(1).setLocations(line1)

    val line2 = gls(2)
    line2.set(0,gridLatLon(radius, Degrees(315)))
    line2.set(1,gridLatLon(radius, Degrees(135)))
    polys(2).setLocations(line2)

    val line3 = gls(3)
    line3.set(0,gridLatLon(radius, Degrees(45)))
    line3.set(1,gridLatLon(radius, Degrees(225)))
    polys(3).setLocations(line3)
  }

  def createPolys = {
    gls.map( new SurfacePolyline(gridAttrs,_))
  }

  def createLabels = {
    val a = new Array[GlobeAnnotation](nRings)
    for (i <- 0 until nRings){
      val r = ringInc * (i+1)
      val p = gridPos(r,Degrees(90))
      a(i) = new GlobeAnnotation(r.toNauticalMiles.toInt.toString,p,labelAttrs)
    }
    a
  }
  def setLabels = {
    for (i <- 0 until nRings) {
      val r = ringInc * (i + 1)
      val p = gridPos(r, Degrees(90))
      labels(i).setPosition(p)
    }
  }

  //--- dynamic operations

  def show = {
    if (!isShowing) {
      isShowing = true
      layer.addRenderable(outerRing)
      innerRings.foreach(layer.addRenderable)
      polys.foreach(layer.addRenderable)
      labels.foreach(layer.addRenderable)
    }
  }

  def hide = {
    if (isShowing) {
      isShowing = false
      layer.removeRenderable(outerRing)
      innerRings.foreach(layer.removeRenderable)
      polys.foreach(layer.removeRenderable)
      labels.foreach(layer.removeRenderable)
    }
  }

  def setCenter (newCenter: GeoPosition) = {
    center = newCenter
    outerRing.setCenter(latLonPos2LatLon(newCenter))
    innerRings.foreach { _.setCenter(latLonPos2LatLon(newCenter)) }
    setGridLines
    setLabels
  }
}
