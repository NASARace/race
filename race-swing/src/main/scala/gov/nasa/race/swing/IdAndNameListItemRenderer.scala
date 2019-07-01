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

import Style._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import scala.swing.{Alignment, Label, ListView}


// the rendering componnet
class IdAndNamePanel[T](id: T=>String, name: T=>String) extends ItemRenderPanel[T] {
  val idLabel = new Label().styled()
  idLabel.horizontalTextPosition = Alignment.Left
  val nameLabel = new Label().styled()

  val c = new Constraints(fill=Fill.Horizontal, anchor=Anchor.West, ipadx=10)
  layout(idLabel) = c(0,0)
  layout(nameLabel) = c(1,0).weightx(0.5)

  def configure (list: ListView[_], isSelected: Boolean, focused: Boolean, item: T, index: Int): Unit ={
    idLabel.text = id(item)
    nameLabel.text = name(item)
  }
}

/**
  * a generic ListRenderer for items that have an id and a name
  */
class IdAndNameListItemRenderer[T](id: T=>String, name: T=>String)
  extends ListItemRenderer[T,IdAndNamePanel[T]](new IdAndNamePanel[T](id,name))

