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

import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.collection.mutable.ListBuffer
import scala.swing.{BoxPanel, Component, Label, Orientation, ScrollPane}


/**
  * a generic panel that shows a number of label/value lines set by its subclasses
  *
  * TODO - should support automatic value truncation based on current width, and clipboard copying for
  * single/all fields via shortcut key or popup
  */
abstract class FieldPanel extends ScrollPane {

  class FieldValueLabel(initValue: String) extends Label(initValue) {
    override def text_= (s: String) = {
      // truncation goes here
      super.text_=(s)
    }
  }

  val lines = ListBuffer.empty[(Component,Component)]

  def addField (fieldName: String, initValue: String=""): FieldValueLabel = {
    val nameField  = new Label(fieldName).styled("fieldName")
    val valueField = new FieldValueLabel(initValue).styled("fieldValue")
    lines += (nameField -> valueField)
    valueField
  }

  def addSeparator = lines += (new Separator().styled() -> null.asInstanceOf[Component])

  /**
    * to be called by concrete subclass after `lines` has been initialized in the constructor chain
    */
  def setContents: Unit = {
    val labelPanel = new GBPanel {
      val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West, insets = scaledInsets(1, 3, 1, 3))
      for ((linePair, i) <- lines.zipWithIndex) {
        if (linePair._2 != null) {
          layout(linePair._1) = c(0, i).weightx(0).gridwidth(1)
          layout(linePair._2) = c(1, i).weightx(0.5)
        } else { // separator line
          layout(linePair._1) = c(0, i).weightx(0.5).gridwidth(2)
        }
      }
    }.styled("fieldGrid")

    contents = labelPanel
  }
}
