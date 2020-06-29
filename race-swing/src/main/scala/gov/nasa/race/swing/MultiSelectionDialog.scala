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

import java.awt.{Dimension, Point}

import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.collection.Seq
import scala.language.existentials
import scala.reflect.ClassTag
import scala.swing.event.MouseClicked
import scala.swing.{Alignment, Button, CheckBox, Dialog, FlowPanel, Label, ListView, ScrollPane}

object MultiSelection {

  /**
    * selection result abstraction that can be used for pattern matching
    * and supports none/any selection shortcuts. While we provide suitable
    * selection Seqs this might not be efficient in case candidate sets are
    * large and all items are selected.
    */
  sealed abstract class Result[T] {
    def selected: Seq[T]
  }

  case class Canceled[T](selected: Seq[T]) extends Result[T]
  case class NoneSelected[T](selected: Seq[T]) extends Result[T]
  case class AllSelected[T](selected: Seq[T]) extends Result[T]
  case class SomeSelected[T](selected: Seq[T]) extends Result[T]
}
import MultiSelection._

/**
  * a modal dialog that represents choices as columns of checkboxes
  */
class MultiSelectionDialog[T:ClassTag] ( dialogTitle: String,
                                         choices: Seq[T],
                                         initialSelections: Seq[T],
                                         labelFunc: T=>String,
                                         descrFunc: T=>String,
                                         maxRows: Int = 25) extends Dialog {

  // note this is only used for rendering - we can't add reactions here
  class ChoiceRenderPanel extends ItemRenderPanel[T] {
    val checkBox: CheckBox = new CheckBox().defaultStyled
    val idLabel: Label = new Label().defaultStyled
    val descrLabel: Label = new Label().defaultStyled

    idLabel.horizontalAlignment = Alignment.Left
    idLabel.preferredSize = new Dimension(Style.getSysFontWidth('M')*6,Style.getSysFontHeight)
    descrLabel.horizontalAlignment = Alignment.Left

    val c = new Constraints(fill=Fill.Horizontal, anchor=Anchor.West)
    layout(checkBox)    = c(0,0)
    layout(idLabel)     = c(1,0)
    layout(descrLabel)  = c(2,0).weightx(0.5).insets(scaledInsets(0,8,0,0))

    def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, item: T, index: Int) = {
      checkBox.selected = selItems.contains(item)
      idLabel.text = labelFunc(item)
      descrLabel.text = descrFunc(item)
    }

    def isToggle (pos: Point): Boolean = checkBox.bounds.contains(pos.x,0)
  }

  title = dialogTitle

  val candidates = choices.sortWith( (a,b) => labelFunc(a) < labelFunc(b)) // TODO - do we need sorting?

  protected var selItems: Seq[T] = initialSelections
  var result: MultiSelection.Result[T] = Canceled(initialSelections)

  val listView = new ListView[T](candidates) with VisibilityBounding[T].defaultStyled
  //listView.maxVisibleRows = maxRows
  val renderPanel = new ChoiceRenderPanel
  listView.renderer = new ListItemRenderer(renderPanel)
  listView.visibleRowCount = Math.min(maxRows,candidates.size)
  listView.maxVisibleRows = maxRows

  listenTo(listView.mouse.clicks)
  reactions += {
    case MouseClicked(_,pos,mod,clicks,triggerPopup) =>
      val t: T = listView.selection.items.head

      if (clicks == 2) { // double click selects single item and closes dialog
        closeWithResult(MultiSelection.SomeSelected(Seq(t)))
      } else  if (renderPanel.isToggle(pos)) {
        if (selItems.contains(t)) selItems = selItems.filter(_ != t)
        else selItems = candidates.filter(a => selItems.contains(a) || a == t) // maintain order
        listView.repaint()
      }
  }

  val sp = new ScrollPane(listView).styled("verticalIfNeeded")
  val buttons =  new FlowPanel(FlowPanel.Alignment.Right)(
    Button("Cancel")(closeWithResult(Canceled(initialSelections))).defaultStyled,
    Button("none")(closeWithResult(NoneSelected(Seq.empty[T]))).defaultStyled,
    Button("all")(closeWithResult(AllSelected(candidates))).defaultStyled,
    Button("Ok")(closeWithResult(SomeSelected(selItems))).defaultStyled
  ).defaultStyled

  contents = new GBPanel {
    val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West)
    layout(sp)      = c(0,0).fill(Fill.Both).anchor(Anchor.NorthWest).weightx(1.0).weighty(1.0)
    layout(buttons) = c(0,1).fill(Fill.None).anchor(Anchor.SouthEast).weightx(0.0).weighty(0.0)
  }.defaultStyled

  modal = true

  protected def closeWithResult(res: MultiSelection.Result[T]): Unit = {
    result = res
    close()
  }

  def process: MultiSelection.Result[T] = {
    open()
    result
  }
}
