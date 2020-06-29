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

import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.collection.Seq
import scala.reflect.ClassTag
import scala.swing.{Action, Button, Label, MainFrame, TextField}

/**
  * a panel for at-most-one selections from a list of items
  */
class AMOSelectionPanel[T:ClassTag] (label: String,
                                     title: String,
                                     allItems: => Seq[T],
                                     selItem: => Option[T],
                                     itemLabelFunc: T=>String,
                                     itemDescrFunc: T=>String,
                                     maxRows: Int = 15)(action: AMOSelection.Result[T] =>Unit) extends GBPanel {

  val lbl = new Label(label).styled("labelFor")

  val tf = new TextField(15).styled("stringField")
  tf.text = getTfText(selItem)
  tf.action = Action("enter"){
    processTextEntry(tf.text)
  }

  val btn = Button("sel") {
    val dialog = new AMOSelectionDialog(title,allItems,selItem,itemLabelFunc,itemDescrFunc,maxRows).defaultStyled
    dialog.setLocationRelativeTo(this)
    val res = dialog.process
    res match {
      case AMOSelection.Canceled(_) => // ignore
      case sel:AMOSelection.NoneSelected[T] => tf.text = "<none>"; action(sel)
      case sel:AMOSelection.OneSelected[T] => tf.text = itemLabelFunc(sel.selected.get); action(sel)
    }
  }.defaultStyled

  //--- layout
  val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West)
  layout(lbl) = c(0,0).weightx(0).insets( scaledInsets(4,0,4,2))
  layout(tf)  = c(1,0).weightx(1.0).insets( scaledInsets(4,0,4,0))
  layout(btn) = c(2,0).weightx(0).insets( scaledInsets(4,2,4,0))

  protected def getTfText(sel: Option[T]): String = {
    if (sel.isDefined) itemLabelFunc(sel.get) else "<none>"
  }

  protected def processTextEntry (s: String): Unit = {
    if (s.isEmpty || s == "<none>") {
      action(AMOSelection.NoneSelected[T](None))
    } else {
      allItems.find(itemLabelFunc(_) == s) match {
        case Some(t) => action( AMOSelection.OneSelected(Some(t)))
        case None => tf.text = s"<unknown $s>"
      }
    }
  }

  // selection got updated outside of panel, update selection text field
  def updateSelection (newSel: Option[T]): Unit = {
    tf.text = getTfText(newSel)
  }
}

object AMOSelectionPanel {
  def main (args:Array[String]): Unit = {
    val candidates = Seq("one", "two", "three", "four", "five", "six")
    var selection: Option[String] = Some("two")

    def processSelection (result: AMOSelection.Result[String]): Unit = {
      selection = result.selected
      println(s"selection result: $result")
    }
    def labelFunc(s: String): String = s
    def descrFunc(s: String): String = s"the string'$s'"

    val top = new MainFrame {
      title = "AMOSelectionDialog Test"
      val result = new Label("<nothing selected yet>")

      contents = new AMOSelectionPanel(
        "selection:",
        "Please select item",
        candidates,
        selection,
        labelFunc,
        descrFunc,
        20
      )(processSelection).defaultStyled
    }.styled("topLevel")

    top.open()
  }
}