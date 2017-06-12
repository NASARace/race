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

import scala.swing.{ComboBox, ListView}

/**
  * a parameterized selection panel for a static list of entries using a ComboBox
  */
class StaticSelectionPanel[T,L <:ListItemRenderPanel[T]](items: Seq[T],listItemRenderer: L) extends GBPanel {

  val combo = new ComboBox[T](items) {
    maximumRowCount = Math.min(items.size, 40)
    renderer = new ListView.AbstractRenderer[T,L](listItemRenderer){
      override def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, a: T, index: Int): Unit = {}
    }
  }
}
