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

package gov.nasa.race.swing

import java.awt.geom.{Path2D, AffineTransform}
import java.lang.Math._
import gov.nasa.race.swing.Direction._
import java.awt._
import javax.swing._
import javax.swing.SwingConstants._
import java.awt.RenderingHints._

import scala.collection.mutable

object ArrowIcon {
  def main (args: Array[String]): Unit = {
    val frame: JFrame = new JFrame
    val panel: JPanel = new JPanel
    frame.add(panel)

    val size = 32
    val color = Color.BLACK
    val width = 2
    val fill = Some(Color.YELLOW)

    panel.add(new JLabel("north", new ArrowIcon(North,size,color,width,fill), CENTER))
    panel.add(new JLabel("west", new ArrowIcon(West,size,color,width,fill), CENTER))
    panel.add(new JLabel("south", new ArrowIcon(South,size,color,width,fill), CENTER))
    panel.add(new JLabel("east", new ArrowIcon(East,size,color,width,fill), CENTER))

    frame.pack
    frame.setVisible(true)
  }

  type CacheKey = (Direction,Int,Color,Int,Option[Color])
  private val cache = mutable.Map.empty[CacheKey,ArrowIcon]

  def apply (direction: Direction, size: Int=16,
             strokeColor: Color = Color.BLACK, strokeWidth: Int = 1, fillColor: Option[Color] = None) = synchronized {
    val key = (direction, size, strokeColor, strokeWidth, fillColor)
    cache.getOrElseUpdate(key, new ArrowIcon(direction, size, strokeColor, strokeWidth, fillColor))
  }
}

/**
  * an icon for arrows with various orientations and colors.
  */
class ArrowIcon (val direction: Direction,
                 val size: Int = 16,
                 val strokeColor: Color = Color.BLACK,
                 val strokeWidth: Int = 1,
                 val fillColor: Option[Color] = None) extends Icon {

  val image = createImage

  override def getIconHeight: Int = size
  override def getIconWidth: Int = size
  override def paintIcon (c: Component, g: Graphics, x: Int, y: Int): Unit = g.drawImage(image, x, y, c)

  protected def createImage: Image = {
    val image = getDefaultConfiguration.createCompatibleImage(size, size, Transparency.TRANSLUCENT)
    val g = image.getGraphics.asInstanceOf[Graphics2D]
    val size2 = size / 2
    val shape = createShape
    val t = new AffineTransform

    direction match {
      case South => // nothing, we draw south
      case North => t.setToRotation(PI, size2,size2)
      case East => t.setToRotation(-PI/2, size2, size2)
      case West => t.setToRotation(PI/2, size2, size2)
    }
    g.setTransform(t)
    g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON)

    if (fillColor.isDefined){
      g.setColor(fillColor.get)
      g.fill(shape)
    }

    g.setColor(strokeColor)
    if (strokeWidth > 1) g.setStroke(new BasicStroke((strokeWidth.toFloat)))
    g.draw(shape)

    image
  }

  def translate (g: Graphics2D, rotation: AffineTransform, dx: Int, dy: Int) = {
    val t1 = new AffineTransform
    t1.setToTranslation(dx,dy)
    t1.concatenate(rotation)
    g.setTransform(t1)
  }

  def createShape = {
    // (0,0) is upper left
    val size2 = (size / 2).toFloat
    val y = (size / 4).toFloat
    val w = size2 * 2 - 1 // needs to be odd

    val shape = new Path2D.Float
    shape.moveTo( 1,         y)             // left origin
    shape.lineTo( 1+w,       y)             // right
    shape.lineTo( size2,     size -1.0)       // bottom
    shape.lineTo( 1,         y)             // and close
    shape
  }
}
