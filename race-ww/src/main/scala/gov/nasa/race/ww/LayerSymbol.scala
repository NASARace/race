/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.awt.image.BufferedImage
import java.awt.{Font, Point}

import gov.nasa.race.loopFromTo
import gov.nasa.race.swing.scaledSize
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.render.{Material, MultiLabelPointPlacemark, Offset, PointPlacemarkAttributes}

/**
  * object that provides initialization for symbol attributes
  * note this does not imply that the respective implementation is a TrackedObject
  */
trait LayerSymbolOwner {
  //--- WW specifics
  def labelMaterial: Material
  def labelFont: Font
  def subLabelFont: Font
  def lineMaterial: Material
  def labelOffset: Offset
  def iconOffset: Offset
  def wwPosition: WWPosition

  def symbolImage: BufferedImage
  def symbolImageScale: Double = 1.0
  def symbolHeading: Double = 0.0

  //--- RACE specifics
  def layer: RaceLayer
  def labelText: String
  def displayName: String             // for info balloons

  // override if there are sublabels
  def numberOfSublabels: Int = 0
  def subLabelText (idx: Int): String = null
}

/**
  * a MultiLabelPointPlacemark that gets its Renderables and attributes from a LayerSymbolOwner, is
  * associated to a RaceLayer and supports pick operations
  *
  * note that pick support mandates that we can link back to the respective RaceLayer, which we delegate to the owner
  */
class LayerSymbol (val owner: LayerSymbolOwner) extends MultiLabelPointPlacemark(owner.wwPosition) with RaceLayerPickable {

  var showDisplayName = false
  var attrs = new PointPlacemarkAttributes

  //--- those are invariant
  attrs.setLabelMaterial(owner.labelMaterial)
  attrs.setLabelFont(owner.labelFont)
  attrs.setLineMaterial(owner.labelMaterial)
  attrs.setLabelOffset(owner.labelOffset)
  setAttributes(attrs)

  setSubLabelFont(owner.subLabelFont)
  setAltitudeMode(WorldWind.ABSOLUTE)


  override def layerItem: AnyRef = owner
  override def layer = owner.layer

  def setDotAttrs = {
    removeAllLabels()
    attrs.setScale( scaledSize(5).toDouble )
    attrs.setImage(null)
    attrs.setUsePointAsDefaultImage(true)
    //setAttributes(attrs)
  }

  def setLabelAttrs = {
    setLabelText(owner.labelText)
    attrs.setScale( scaledSize(5).toDouble)
    attrs.setImage(null)
    attrs.setUsePointAsDefaultImage(true)

    //setAttributes(attrs)
  }

  def setIconAttrs = {
    setLabelText(owner.labelText)
    attrs.setImage(owner.symbolImage)
    attrs.setScale(owner.symbolImageScale)
    attrs.setImageOffset(Offset.CENTER)
    attrs.setHeading(owner.symbolHeading)
    attrs.setHeadingReference(AVKey.RELATIVE_TO_GLOBE)

    // TODO check view pitch and adjust symbol accordingly
    //setAttributes(attrs)
  }

  def setDisplayName(showIt:Boolean) = {
    if (showIt) {
      showDisplayName = true
      updateDisplayName
    } else showDisplayName = false
  }

  def updateDisplayName = setValue( AVKey.DISPLAY_NAME, owner.displayName)

  def update  = {
    setPosition(owner.wwPosition)
    attrs.setHeading(owner.symbolHeading)

    if (hasLabel) setLabelText(owner.labelText)
    if (hasSubLabels) loopFromTo(0,owner.numberOfSublabels) { i=> setSubLabelText(i, owner.subLabelText(i)) }

    if (showDisplayName) updateDisplayName
  }

  //--- cache so that we don't create gazillions of Points and Offsets
  val screenPt = {
    if (screenPoint != null) new Point(screenPoint.x.toInt, screenPoint.y.toInt) else new Point(0,0)
  }
  val screenOffset = {
    if (screenPoint != null) new Offset(screenPoint.x,screenPoint.y,AVKey.PIXELS,AVKey.PIXELS)
    else new Offset(.0,.0,AVKey.PIXELS,AVKey.PIXELS)
  }

  def getScreenPt: Point = {
    if (screenPoint != null) screenPt.setLocation(screenPoint.x, screenPoint.y)
    screenPt
  }

  def getScreenOffset: Offset = {
    if (screenPoint != null){ screenOffset.setX(screenPoint.x); screenOffset.setY(screenPoint.y) }
    screenOffset
  }
  def screenPointX = screenPoint.x
  def screenPointY = screenPoint.y
}
