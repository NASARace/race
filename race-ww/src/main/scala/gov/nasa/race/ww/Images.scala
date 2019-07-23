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

import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt._

import gov.nasa.race.swing._
import javax.imageio.ImageIO
import javax.swing.ImageIcon

import scala.swing._

/**
  * commonly used images
  *
  * general policy is to use screen resolution/DPI specific images and avoid scaling, hence
  * the odd sizes (which have to support centering)
  */
object Images {

  val sizePostfix: String =  {
    if (uiScale >= hidpiScale) "-hidpi" else ""
  }

  def colorPostfix (clr: Color): String = {
    clr match {
      case Color.white => "white"
      //case Color.black => "black"
      case Color.red => "red"
      case Color.green => "green"
      case Color.blue => "blue"
      case Color.yellow => "yellow"
      case Color.cyan => "cyan"
      case Color.magenta => "magenta"
      //case Color.orange => "orange"
      case _ => "yellow" // default
    }
  }

  def loadCursor (cursorName: String, resourceBaseName: String): Cursor = {
    val size = if (uiScale >= 2.0) 65 else 33
    val url = getClass.getResource(s"$resourceBaseName-$size.png")
    val icon = Swing.Icon(url)
    val hotSpot = new Point(icon.getIconWidth/2,icon.getIconHeight/2)
    Toolkit.getDefaultToolkit.createCustomCursor(icon.getImage,hotSpot,cursorName)
  }

  def loadImage(shape: String, color: Color): BufferedImage = {
    val fname = s"$shape-${colorPostfix(color)}$sizePostfix.png"
    ImageIO.read(getClass.getResourceAsStream(fname))
  }

  def readScaled(fname: String, factor: Float = gov.nasa.race.swing.uiScale): BufferedImage = {
    val img = ImageIO.read(getClass.getResourceAsStream(fname))

    if (factor != 1.0f){
      val w = Math.round(img.getWidth * factor)
      val h = Math.round(img.getHeight * factor)
      val scaledImg = new BufferedImage(w, h, img.getType)
      val g: Graphics2D = scaledImg.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      val at = AffineTransform.getScaleInstance(factor, factor)
      g.drawRenderedImage(img, at)
      scaledImg

    } else {
      img
    }
  }

  def getIcon(fname: String) = Swing.Icon(getClass.getResource(fname))

  def getScaledIcon(fname: String, factor: Float = gov.nasa.race.swing.uiScale): ImageIcon = {
    val icon = getIcon(fname)
    if (factor != 1.0f){
      val img = icon.getImage
      val w = Math.round(icon.getIconWidth * factor)
      val h = Math.round(icon.getIconHeight * factor)
      new ImageIcon(img.getScaledInstance(w,h,Image.SCALE_DEFAULT))
    } else {
      icon
    }
  }

  def scale(sbi: BufferedImage, imageType: Int, dWidth: Int, dHeight: Int, fWidth: Double, fHeight: Double): BufferedImage = {
    if (sbi != null) {
      val dbi = new BufferedImage(dWidth, dHeight, imageType)
      val g: Graphics2D = dbi.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      val at = AffineTransform.getScaleInstance(fWidth, fHeight)
      g.drawRenderedImage(sbi, at)
      dbi
    } else throw new RuntimeException("failed to scale image")
  }

  val defaultPlaneImgs = Map[Color, BufferedImage](
    Color.red -> loadImage("plane", Color.red),
    Color.yellow -> loadImage("plane", Color.yellow),
    Color.cyan -> loadImage("plane", Color.cyan),
    Color.green -> loadImage("plane", Color.green),
    Color.blue -> loadImage("plane", Color.blue),
    Color.magenta -> loadImage("plane", Color.magenta),
    Color.white -> loadImage("plane", Color.white),
    //Color.black -> loadImage("plane", Color.black)
  )
  val defaultPlaneImg = defaultPlaneImgs(Color.red)
  def getPlaneImage(color: Color) = defaultPlaneImgs.getOrElse(color, defaultPlaneImg)

  val defaultArrowImgs = Map[Color, BufferedImage](
    Color.red -> loadImage("arrow", Color.red),
    Color.yellow -> loadImage("arrow", Color.yellow),
    Color.cyan -> loadImage("arrow", Color.cyan),
    Color.green -> loadImage("arrow", Color.green),
    Color.blue -> loadImage("arrow", Color.blue),
    Color.magenta -> loadImage("arrow", Color.magenta),
    Color.white -> loadImage("arrow", Color.white),
    //Color.black -> loadImage("arrow", Color.black)
  )
  val defaultArrowImg = defaultArrowImgs(Color.green)
  def getArrowImage(color: Color) = defaultArrowImgs.getOrElse(color,defaultArrowImg)

  val defaultMarkImgs = Map[Color,BufferedImage](
    Color.red -> loadImage("mark", Color.red),
    Color.yellow -> loadImage("mark", Color.yellow),
    Color.cyan -> loadImage("mark", Color.cyan),
    Color.green -> loadImage("mark", Color.green),
    Color.blue -> loadImage("mark", Color.blue),
    Color.magenta -> loadImage("mark", Color.magenta),
    Color.white -> loadImage("mark", Color.white),
    //Color.black -> loadImage("mark", Color.black)
  )
  val defaultMarkImg = defaultMarkImgs(Color.white)
  def getMarkImage(color: Color) = defaultMarkImgs.getOrElse(color, defaultMarkImg)


  val defaultEventImgs = Map[Color,BufferedImage](
    Color.red -> loadImage("event", Color.red),
    Color.yellow -> loadImage("event", Color.yellow),
    Color.cyan -> loadImage("event", Color.cyan),
    Color.green -> loadImage("event", Color.green),
    Color.blue -> loadImage("event", Color.blue),
    Color.magenta -> loadImage("event", Color.magenta),
    Color.white -> loadImage("event", Color.white),
    //Color.black -> loadImage("event", Color.black)
  )
  val defaultEventImg = defaultEventImgs(Color.red)
  def getEventImage(color: Color) = defaultEventImgs.getOrElse(color,defaultEventImg)

  //--- flight entry attribute symbols (only one color so far - we might create others programmatically)

  val blankFlightIcon = getScaledIcon("flight-blank-7x16.png")

  val defaultFlightCenterIcon = getScaledIcon("flight-centered-white-7x16.png")
  def getFlightCenteredIcon(color: Color) = defaultFlightCenterIcon

  val defaultFlightHiddenIcon = getScaledIcon("flight-hidden-white-7x16.png")
  def getFlightHiddenIcon(color: Color) = defaultFlightHiddenIcon

  val defaultFlightPathIcon = getScaledIcon("flight-path-white-7x16.png")
  def getFlightPathIcon(color: Color) = defaultFlightPathIcon

  val defaultFlightInfoIcon = getScaledIcon("flight-info-white-7x16.png")
  def getFlightInfoIcon(color: Color) = defaultFlightInfoIcon

  val defaultFlightMarkIcon = getScaledIcon("flight-mark-white-7x16.png")
  def getFlightMarkIcon(color: Color) = defaultFlightMarkIcon

}
