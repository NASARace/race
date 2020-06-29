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
import scala.reflect.ClassTag
import scala.swing.event.MouseClicked
import scala.swing.{Alignment, Button, Dialog, FlowPanel, Label, ListView, RadioButton, ScrollPane}

object AMOSelection {
  sealed abstract class Result[T]{
    def selected: Option[T]
  }
  case class Canceled[T](selected: Option[T]) extends Result[T]
  case class NoneSelected[T](selected: Option[T]=None) extends Result[T]
  case class OneSelected[T](selected: Option[T]) extends Result[T]
}
import AMOSelection._

/**
  * at-most-one selection dialog, which allows cancel/none-/one-selected operations
  * over a set of mutually exclusive choices
  */
class AMOSelectionDialog[T:ClassTag] ( dialogTitle: String,
                                       choices: Seq[T],
                                       initialSelection: Option[T],
                                       labelFunc: T=>String,
                                       descrFunc: T=>String,
                                       maxRows: Int = 25) extends Dialog {

  class ChoiceRenderPanel extends ItemRenderPanel[T] {
    val radio: RadioButton = new RadioButton().defaultStyled
    val idLabel: Label = new Label().defaultStyled
    val descrLabel: Label = new Label().defaultStyled

    idLabel.horizontalAlignment = Alignment.Left
    idLabel.preferredSize = new Dimension(Style.getSysFontWidth('M')*6,Style.getSysFontHeight)
    descrLabel.horizontalAlignment = Alignment.Left

    val c = new Constraints(fill=Fill.Horizontal, anchor=Anchor.West)
    layout(radio)       = c(0,0)
    layout(idLabel)     = c(1,0)
    layout(descrLabel)  = c(2,0).weightx(0.5).insets(scaledInsets(0,8,0,0))

    def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, item: T, index: Int) = {
      radio.selected = selItem.isDefined && item == selItem.get
      idLabel.text = labelFunc(item)
      descrLabel.text = descrFunc(item)
    }

    def isToggle (pos: Point): Boolean = radio.bounds.contains(pos.x,0)
  }

  title = dialogTitle

  val candidates = choices.sortWith( (a,b) => labelFunc(a) < labelFunc(b)) // TODO - do we need sorting?
  var selItem: Option[T] = initialSelection
  var result: AMOSelection.Result[T] = Canceled(initialSelection)

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
        closeWithResult(OneSelected(Some(t))) // shortcut for select-and-close
      } else {
        selItem = Some(t)
        listView.repaint()
      }
  }

  val sp = new ScrollPane(listView).styled("verticalIfNeeded")
  val buttons =  new FlowPanel(FlowPanel.Alignment.Right)(
    Button("Cancel")(closeWithResult(Canceled(initialSelection))).defaultStyled,
    Button("none")(closeWithResult(NoneSelected())).defaultStyled,
    Button("Ok")(closeWithResult( if (selItem.isDefined) OneSelected(selItem) else NoneSelected())).defaultStyled
  ).defaultStyled

  contents = new GBPanel {
    val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West)
    layout(sp)      = c(0,0).fill(Fill.Both).anchor(Anchor.NorthWest).weightx(1.0).weighty(1.0)
    layout(buttons) = c(0,1).fill(Fill.None).anchor(Anchor.SouthEast).weightx(0.0).weighty(0.0)
  }.defaultStyled

  modal = true
  def closeWithResult (res: AMOSelection.Result[T]): Unit = {
    result = res
    close()
  }

  def process: AMOSelection.Result[T] = {
    open()
    result
  }
}
