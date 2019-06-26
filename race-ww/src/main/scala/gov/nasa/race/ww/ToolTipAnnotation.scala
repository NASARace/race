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

import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.render._
import java.awt._

import gov.nasa.race.common._
import gov.nasa.race.swing.Style

class InfoBalloon (text: String) extends ScreenAnnotation(text, new Point(0,0)) {
  attributes.setAdjustWidthToText(AVKey.SIZE_FIT_TEXT)
  attributes.setFrameShape(AVKey.SHAPE_RECTANGLE)
  attributes.setTextColor(Style.getColor("balloonText"))
  attributes.setBackgroundColor(Style.getColor("balloonBackground"))
  attributes.setCornerRadius(5)
  attributes.setBorderColor(Style.getColor("balloonBorder"))
  attributes.setFont(Style.getFont("balloonText"))
  attributes.setTextAlign(AVKey.LEFT)
  attributes.setInsets(new Insets(3, 3, 3, 3))

  def setDrawOffset (pt: Point) = attributes.setDrawOffset(pt)
}

class ToolTipAnnotation (text: String) extends InfoBalloon(text) {
  protected var tooltipOffset = new Point(5,5)

  def getTooltipOffset = tooltipOffset
  def setTooltipOffset(pt: Point) = tooltipOffset = pt
  def getOffsetX =  if (tooltipOffset != null) tooltipOffset.x else 0
  def getOffsetY =  if (tooltipOffset != null) tooltipOffset.y else 0

  override def doRenderNow (dc: DrawContext): Unit = {
    if (dc.getPickPoint != null){
      attributes.setDrawOffset(new Point(getBounds(dc).width / 2 + getOffsetX, getOffsetY))
      setScreenPoint(adjustDrawPointToViewport(dc.getPickPoint(), getBounds(dc), dc.getView().getViewport()))
    }
    super.doRenderNow(dc)
  }

  protected def adjustDrawPointToViewport(point: Point, bounds: Rectangle, viewport: Rectangle): Point = {
    var x = point.x
    var y = viewport.getHeight.toInt - point.y -1

    if (x + this.getOffsetX + bounds.getWidth() > viewport.getWidth())
      x = ((viewport.getWidth() - bounds.getWidth()) - 1 - getOffsetX).toInt
    else if (x < 0)
      x = 0

    if (y + getOffsetY + bounds.getHeight() > viewport.getHeight())
      y = ((viewport.getHeight() - bounds.getHeight()) - 1 - getOffsetY).toInt
    else if (y < 0)
      y = bounds.height;

    new Point(x, y)
  }
}
