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
  *
  * note that we need functions to initialize allItems and selItems since those sets
  * can change over the lifetime of the panel (as opposed to the lifetime of the MultiSelectionDialog)
  */
class MultiSelectionPanel[T:ClassTag] (label: String,
                                       title: String,
                                       allItems: => Seq[T],
                                       selItems: => Seq[T],
                                       itemLabelFunc: T=>String,
                                       itemDescrFunc: T=>String,
                                       maxRows: Int = 15)(action: MultiSelection.Result[T] =>Unit) extends GBPanel {

  val lbl = new Label(label).styled("labelFor")

  val tf = new TextField(15).styled("stringField")
  tf.text = getTfText(selItems)
  tf.action = Action("enter"){
    processTextEntry(tf.text)
  }

  val btn = Button("sel"){
    val dialog = new MultiSelectionDialog(title,allItems,selItems,itemLabelFunc,itemDescrFunc,maxRows).defaultStyled
    dialog.setLocationRelativeTo(this)
    val res = dialog.process
    res match {
      case MultiSelection.Canceled(_) => // do nothing
      case MultiSelection.NoneSelected(_) => tf.text = "<none>"; action(res)
      case MultiSelection.AllSelected(_) => tf.text = "<all>"; action(res)
      case MultiSelection.SomeSelected(sel) => tf.text = getTfText(sel); action(res)
    }
  }.defaultStyled


  def processTextEntry(s: String): Unit = {
    val selLabels = s.split("[ ,]+")
    var selections = Seq.empty[T]
    val res = if (isNoneSelection(selLabels)){
      MultiSelection.NoneSelected(selections)
    } else if (isAllSelection(selLabels)) {
      selections = allItems
      MultiSelection.AllSelected(selections)
    } else {
      selections = allItems.filter( t=> selLabels.contains(itemLabelFunc(t)))
      MultiSelection.SomeSelected(selections)
    }
    tf.text = getTfText(selections)
    action(res)
  }

  def isAllSelection (as: Array[String]): Boolean = {
    as.foreach { s =>
      if (s == "*" || s == "<all>" || s == "<any>") return true
    }
    false
  }

  def isNoneSelection (as: Array[String]): Boolean = {
    if (as.isEmpty) {
      true
    } else {
      as.foreach { s =>
        if (s == "<none>") return true
      }
      false
    }
  }

  // selection got updated outside of panel, update selection text field
  def updateSelection (newSel: Seq[T]): Unit = {
    tf.text = getTfText(newSel)
  }

  //--- layout
  val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West)
  layout(lbl) = c(0,0).weightx(0).insets( scaledInsets(4,0,4,2))
  layout(tf)  = c(1,0).weightx(1.0).insets( scaledInsets(4,0,4,0))
  layout(btn) = c(2,0).weightx(0).insets( scaledInsets(4,2,4,0))

  protected def getTfText (sel: Seq[T]): String = {
    if (sel.isEmpty) "<none>"
    else if (sel == allItems) "<all>"
    else sel.map(itemLabelFunc).mkString(",")
  }
}

object MultiSelectionPanel {
  def main (args:Array[String]): Unit = {
    val candidates = Seq("one", "two", "three", "four", "five", "six")
    var selections = Seq("two")

    def processSelection (result: MultiSelection.Result[String]): Unit = {
      selections = result.selected
      println(s"selection result: $result")
    }
    def labelFunc(s: String): String = s
    def descrFunc(s: String): String = s"the string'$s'"

    val top = new MainFrame {
      title = "MultiSelectionDialog Test"
      val result = new Label("<nothing selected yet>")

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

    top.open()
  }
}