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
package gov.nasa.race.swing

import java.awt.Insets

import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.collection.Seq
import scala.reflect.ClassTag
import scala.swing.{Action, Button, Label, MainFrame, TextField}


/**
  * a panel for multi-item selection
  */
class MultiSelectionPanel[T:ClassTag] (label: String,
                              title: String,
                              var items: Seq[T],
                              var selections: Seq[T],
                              itemLabelFunc: T=>String,
                              itemDescrFunc: T=>String,
                              maxRows: Int = 15)(action: (Result[T])=>Unit) extends GBPanel {

  val lbl = new Label(label).styled("labelFor")

  val tf = new TextField(20).styled("stringField")
  tf.text = getTfText(selections)
  tf.action = Action("enter"){
    processTextEntry(tf.text)
  }

  val btn = Button("sel"){
    val dialog = new MultiSelectionDialog(title,items,selections,itemLabelFunc,itemDescrFunc,maxRows).defaultStyled
    dialog.setLocationRelativeTo(this)
    val res = dialog.process
    selections = res.selected
    res match {
      case Canceled(_) => // do nothing
      case NoneSelected(_) => tf.text = ""; action(res)
      case AllSelected(_) => tf.text = "*"; action(res)
      case SomeSelected(sel) => tf.text = getTfText(sel); action(res)
    }
  }.defaultStyled


  def processTextEntry(s: String): Unit = {
    val selLabels = s.split("[ ,]+")
    val res = if (selLabels.isEmpty){
      selections = Seq.empty[T]
      NoneSelected(selections)
    } else if (selLabels.contains("*")) {
      selections = items
      AllSelected(selections)
    } else {
      selections = items.filter( t=> selLabels.contains(itemLabelFunc(t)))
      SomeSelected(selections)
    }
    tf.text = getTfText(selections)
    action(res)
  }

  //--- layout
  val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West)
  layout(lbl) = c(0,0).weightx(0).insets( scaledInsets(4,8,4,2))
  layout(tf)  = c(1,0).weightx(1.0).insets( scaledInsets(4,0,4,0))
  layout(btn) = c(2,0).weightx(0).insets( scaledInsets(4,2,4,4))

  protected def getTfText (sel: Seq[T]): String = {
    if (sel.isEmpty) ""
    else if (sel.size == items.size) "*"
    else sel.map(itemLabelFunc).mkString(",")
  }
}

object MultiSelectionPanel {
  def main (args:Array[String]): Unit = {
    val top = new MainFrame {
      title = "MultiSelectionDialog Test"
      val result = new Label("<nothing selected yet>")
      val candidates = Seq("one", "two", "three", "four", "five", "six")
      var selections = Seq("two")

      contents = new MultiSelectionPanel(
        "selections:",
        "Please select item",
        candidates,
        selections,
        labelFunc,
        descrFunc,
        20
      )(processSelection).defaultStyled
    }.styled("topLevel")

    top.open
  }

  def processSelection (result: Result[String]): Unit = println(s"selection result: $result")
  def labelFunc(s: String): String = s
  def descrFunc(s: String): String = s"the string'$s'"
}