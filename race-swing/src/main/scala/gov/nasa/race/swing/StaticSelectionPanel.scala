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

import scala.swing.event.SelectionChanged
import scala.swing.{ComboBox, Label}

/**
  * a parameterized selection panel for a static list of entries using a ComboBox
  */
class StaticSelectionPanel[T,L <:ItemRenderPanel[T]](label: String, items: Seq[T], maxRows: Int,
                                                     itemRenderPanel: L, selectAction: T=>Unit) extends GBPanel {
  val combo = new ComboBox[T](items) {
    maximumRowCount = maxRows
    renderer = new ListItemRenderer[T,ItemRenderPanel[T]](itemRenderPanel)
  } styled()

  val c = new Constraints( fill=Fill.Horizontal, anchor=Anchor.West, insets=(8,2,0,2))
  layout(new Label(label).styled('labelFor)) = c(0,0).weightx(0.5)
  layout(combo) = c(1,0).weightx(0)

  listenTo(combo.selection)
  reactions += {
    case SelectionChanged(`combo`) => selectAction(combo.selection.item)
  }
}
