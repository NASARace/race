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

import scala.swing.{Alignment, Label, Swing}

object OnOffIndicator {
  val onIcon = Swing.Icon(getClass.getResource("led-green.png"))
  val offIcon = Swing.Icon(getClass.getResource("led-red.png"))
}
import OnOffIndicator._

/**
  * an icon label that indicates on/off state
  */
class OnOffIndicator (txt: String, align: Alignment.Value, var isOn: Boolean) extends Label {

  def this() = this("",Alignment.Center, false)
  def this(isOn: Boolean) = this("",Alignment.Center, isOn)
  def this(txt: String, isOn: Boolean) = this(txt,Alignment.Center,isOn)

  icon = if (isOn) onIcon else offIcon
  text = txt

  def on = icon = onIcon
  def off = icon = offIcon
}
