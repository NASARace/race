/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import scala.reflect.ClassTag
import scala.swing.event.SelectionChanged
import scala.swing.{ComboBox, Label}

/**
  * a parameterized selection panel for a list of entries using a ComboBox
  */
abstract class SelectionPanel[T:ClassTag,L <:ItemRenderPanel[T]] (label: String,
                                                                  items: Seq[T],
                                                                  maxRows: Int,
                                                                  itemRenderPanel: L,
                                                                  selectAction: T=>Unit)
                                                                               extends GBPanel {
  val combo: ComboBox[T] = createCombo

  combo.maximumRowCount = maxRows
  combo.renderer = new ListItemRenderer[T,ItemRenderPanel[T]](itemRenderPanel)

  val c = new Constraints( fill=Fill.Horizontal, anchor=Anchor.West, insets=scaledInsets(8,2,0,2))
  layout(new Label(label).styled("labelFor")) = c(0,0).weightx(0.5)
  layout(combo) = c(1,0).weightx(0)

  listenTo(combo.selection)
  reactions += {
    case SelectionChanged(`combo`) => selectAction(combo.selection.item)
  }

  def createCombo: ComboBox[T]
}


class StaticSelectionPanel[T: ClassTag,L <:ItemRenderPanel[T]](label: String,
                                                     items: Seq[T],
                                                     maxRows: Int,
                                                     itemRenderPanel: L,
                                                     selectAction: T=>Unit)
                          extends SelectionPanel[T,L](label,items,maxRows,itemRenderPanel,selectAction) {
  override def createCombo: ComboBox[T] = new ComboBox[T](items).styled()
}


class DynamicSelectionPanel[T: ClassTag,L <:ItemRenderPanel[T]](label: String,
                                                      items: Seq[T],
                                                      maxRows: Int,
                                                      itemRenderPanel: L,
                                                      selectAction: T=>Unit)
                            extends SelectionPanel[T,L](label,items,maxRows,itemRenderPanel,selectAction) {

  override def createCombo: ComboBox[T] = new DynamicComboBox[T](items).styled()

  def addItem(t: T): Unit = combo.asInstanceOf[DynamicComboBox[T]].addItem(t)
  def removeItem(t: T): Unit = combo.asInstanceOf[DynamicComboBox[T]].removeItem(t)
}
