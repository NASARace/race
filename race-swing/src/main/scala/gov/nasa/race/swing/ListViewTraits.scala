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

import scala.swing.ListView
import scala.collection.Seq

/**
  * a ListView trait that preserves selections and viewport
  */
trait SelectionPreserving[A] extends ListView[A] {

  var itemMapper: (A,Seq[A]) => Boolean = (a,seq) => seq.contains(a)

  abstract override def listData_=(items: Seq[A]): Unit = {
    if (items != listData) { // otherwise no need to change listData
      val selIndices = getNewSelectionIndices(selection.items,items)
      val topIdx = peer.getFirstVisibleIndex

      super.listData_=(items) // this resets the selection

      peer.setSelectedIndices(selIndices)
      if (topIdx < listData.size) peer.ensureIndexIsVisible(topIdx)
    }
    repaint() // but we still need to repaint in case render data inside items has changed
  }

  private def getNewSelectionIndices(oldItems: Seq[A], newItems: Seq[A]): Array[Int] = {
    // according to JList, indices above the limit are ignored in setSelectedIndices()
    val indices: Array[Int] = Array.fill(oldItems.size)(Int.MaxValue)
    var idx=0
    var j = 0
    newItems.foreach { e =>
      if (itemMapper(e,oldItems)) {
        indices(j) = idx
        j += 1
      }
      idx += 1
    }
    indices
  }
}

/**
  * a ListView trait that automatically adjusts a bounded visibleRowCount
  * TODO - ensure minimum size to avoid collapsing views
  */
trait VisibilityBounding[A] extends ListView[A] {
  protected var _maxVisibleRows = Int.MaxValue
  //minimumSize = new Dimension(200,100)

  def maxVisibleRows: Int = _maxVisibleRows
  def maxVisibleRows_=(maxRows: Int): Unit = {
    _maxVisibleRows = maxRows
    if (listData.nonEmpty) adjustVisibleRowCount(listData)
  }

  def adjustVisibleRowCount (items: Seq[A]): Unit = {
    var n = items.size
    if (n > _maxVisibleRows) n = _maxVisibleRows
    if (visibleRowCount != n) {
      visibleRowCount = n
      revalidate()
    }
  }

  abstract override def listData_=(items: Seq[A]): Unit = {
    adjustVisibleRowCount(items)
    super.listData_=(items)
  }
}
