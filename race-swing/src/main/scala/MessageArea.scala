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

import java.awt.{Toolkit, Font, Color}
import scala.swing.{Swing, Label}

object MessageArea {
  val emptyInfoImg = Swing.Icon(getClass.getResource("empty_info.png"))
  val infoImg = Swing.Icon(getClass.getResource("info.png"))
  val alertImg = Swing.Icon(getClass.getResource("alert.png"))
}
import MessageArea._

/**
  * a Label that is used to display message lines
  */
class MessageArea extends Label {
  var normalColor: Color = _
  var normalFont: Font = _
  var alertColor: Color = _
  var alertFont: Font = _
  lazy val initialize = {
    normalFont = peer.getFont
    if (normalColor == null) normalColor = peer.getForeground
    if (alertColor == null) alertColor = Color.red
    if (alertFont == null) alertFont = normalFont // peer.getFont.deriveFont(Font.BOLD)
    true
  }

  icon = emptyInfoImg

  def beep = {
    Toolkit.getDefaultToolkit().beep()
  }

  def info (msg: String) = {
    initialize
    foreground = normalColor
    font = normalFont
    icon = infoImg
    text = msg
  }

  def alert (msg: String) = {
    initialize
    foreground = alertColor
    font = alertFont
    icon = alertImg
    text = msg
    beep
  }

  def clear = {
    initialize
    foreground = normalColor
    font = normalFont
    icon = emptyInfoImg
    text = ""
  }
}
