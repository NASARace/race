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

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.Icon
import scala.swing._

/**
  * commonly used images
  */
object Images {
  val defaultPlaneImgs = Map[Color, BufferedImage](
    Color.red -> ImageIO.read(getClass.getResourceAsStream("plane-red-64x64.png")),
    Color.orange -> ImageIO.read(getClass.getResourceAsStream("plane-orange-64x64.png")),
    Color.yellow -> ImageIO.read(getClass.getResourceAsStream("plane-yellow-64x64.png")),
    Color.cyan -> ImageIO.read(getClass.getResourceAsStream("plane-cyan-64x64.png")),
    Color.green -> ImageIO.read(getClass.getResourceAsStream("plane-green-64x64.png")),
    Color.blue -> ImageIO.read(getClass.getResourceAsStream("plane-blue-64x64.png")),
    Color.magenta -> ImageIO.read(getClass.getResourceAsStream("plane-magenta-64x64.png")),
    Color.white -> ImageIO.read(getClass.getResourceAsStream("plane-white-64x64.png")),
    Color.black -> ImageIO.read(getClass.getResourceAsStream("plane-64x64.png"))
  )
  val defaultPlaneImg = defaultPlaneImgs(Color.red)
  def getPlaneImage(color: Color) = defaultPlaneImgs.getOrElse(color, defaultPlaneImg)

  val defaultArrowImgs = Map[Color, BufferedImage](
    Color.red -> ImageIO.read(getClass.getResourceAsStream("arrow-red-64x64.png")),
    Color.orange -> ImageIO.read(getClass.getResourceAsStream("arrow-orange-64x64.png")),
    Color.yellow -> ImageIO.read(getClass.getResourceAsStream("arrow-yellow-64x64.png")),
    Color.cyan -> ImageIO.read(getClass.getResourceAsStream("arrow-cyan-64x64.png")),
    Color.green -> ImageIO.read(getClass.getResourceAsStream("arrow-green-64x64.png")),
    Color.blue -> ImageIO.read(getClass.getResourceAsStream("arrow-blue-64x64.png")),
    Color.magenta -> ImageIO.read(getClass.getResourceAsStream("arrow-magenta-64x64.png")),
    Color.white -> ImageIO.read(getClass.getResourceAsStream("arrow-white-64x64.png")),
    Color.black -> ImageIO.read(getClass.getResourceAsStream("arrow-64x64.png"))
  )
  val defaultArrowImg = defaultArrowImgs(Color.green)
  def getArrowImage(color: Color) = defaultArrowImgs.getOrElse(color,defaultArrowImg)


  val defaultMarkImgs = Map[Color,BufferedImage](
    Color.red -> ImageIO.read(getClass.getResourceAsStream("mark-red-32x32.png")),
    Color.orange -> ImageIO.read(getClass.getResourceAsStream("mark-orange-32x32.png")),
    Color.yellow -> ImageIO.read(getClass.getResourceAsStream("mark-yellow-32x32.png")),
    Color.cyan -> ImageIO.read(getClass.getResourceAsStream("mark-cyan-32x32.png")),
    Color.green -> ImageIO.read(getClass.getResourceAsStream("mark-green-32x32.png")),
    Color.blue -> ImageIO.read(getClass.getResourceAsStream("mark-blue-32x32.png")),
    Color.magenta -> ImageIO.read(getClass.getResourceAsStream("mark-magenta-32x32.png")),
    Color.white -> ImageIO.read(getClass.getResourceAsStream("mark-white-32x32.png")),
    Color.black -> ImageIO.read(getClass.getResourceAsStream("mark-32x32.png"))
  )
  val defaultMarkImg = ImageIO.read(getClass.getResourceAsStream("mark-32x32.png"))
  def getMarkImage(color: Color) = defaultMarkImgs.getOrElse(color, defaultMarkImg)


  val defaultEventImgs = Map[Color,BufferedImage](
    Color.red -> ImageIO.read(getClass.getResourceAsStream("event-red-32x32.png")),
    Color.orange -> ImageIO.read(getClass.getResourceAsStream("event-orange-32x32.png")),
    Color.yellow -> ImageIO.read(getClass.getResourceAsStream("event-yellow-32x32.png")),
    Color.cyan -> ImageIO.read(getClass.getResourceAsStream("event-cyan-32x32.png")),
    Color.green -> ImageIO.read(getClass.getResourceAsStream("event-green-32x32.png")),
    Color.blue -> ImageIO.read(getClass.getResourceAsStream("event-blue-32x32.png")),
    Color.magenta -> ImageIO.read(getClass.getResourceAsStream("event-magenta-32x32.png")),
    Color.white -> ImageIO.read(getClass.getResourceAsStream("event-white-32x32.png")),
    Color.black -> ImageIO.read(getClass.getResourceAsStream("event-32x32.png"))
  )
  val defaultEventImg = defaultEventImgs(Color.red)
  def getEventImage(color: Color) = defaultEventImgs.getOrElse(color,defaultEventImg)


  //--- flight entry attribute symbols (only one color so far - we might create others programmatically)
  val blankFlightIcon = Swing.Icon(getClass.getResource("flight-blank-7x16.png"))

  val defaultFlightCenterIcon = Swing.Icon(getClass.getResource("flight-centered-white-7x16.png"))
  def getFlightCenteredIcon(color: Color) = defaultFlightCenterIcon

  val defaultFlightHiddenIcon = Swing.Icon(getClass.getResource("flight-hidden-white-7x16.png"))
  def getFlightHiddenIcon(color: Color) = defaultFlightHiddenIcon

  val defaultFlightPathIcon = Swing.Icon(getClass.getResource("flight-path-white-7x16.png"))
  def getFlightPathIcon(color: Color) = defaultFlightPathIcon

  val defaultFlightInfoIcon = Swing.Icon(getClass.getResource("flight-info-white-7x16.png"))
  def getFlightInfoIcon(color: Color) = defaultFlightInfoIcon

  val defaultFlightMarkIcon = Swing.Icon(getClass.getResource("flight-mark-white-7x16.png"))
  def getFlightMarkIcon(color: Color) = defaultFlightMarkIcon

  def getIcon(fname: String) = Swing.Icon(getClass.getResource(fname))
}
