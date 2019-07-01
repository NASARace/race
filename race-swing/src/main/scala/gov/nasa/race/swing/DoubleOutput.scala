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

import java.awt.Color

import gov.nasa.race.common._
import gov.nasa.race.swing.Style._
import scala.swing.{Alignment, Label, FlowPanel, TextField}

/**
 * read-only text field to show formatted double variables
 */
class DoubleOutputField (val varName: String, val fmt: String,
                         val doubleOutputLength: Int=12, val doubleOutputLabelLength: Int=6)
                           extends FlowPanel (FlowPanel.Alignment.Right)(){
  val labelFmt = s"%${doubleOutputLabelLength}.${doubleOutputLabelLength}s"
  val label = new Label().styled("fieldLabel")
  setLabel(varName)

  val value = new TextField(doubleOutputLength).styled("numField")
  value.editable = false
  value.horizontalAlignment = Alignment.Right
  setValue(0.0)

  val tfSize = value.preferredSize
  val lblSize = label.preferredSize
  preferredSize = (tfSize.width + lblSize.width, tfSize.height)

  contents ++= Seq(label,value)

  def setLabel (s: String): Unit = label.text = String.format(labelFmt, s)

  def setValue (v: Double): Unit = value.text = String.format(fmt,Double.box(v))

  def setForeground( color: Color): Unit = value.foreground = color
}
